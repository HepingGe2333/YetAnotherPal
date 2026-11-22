import CoreGraphics
import CryptoKit
import Foundation
import ImageIO

#if canImport(UIKit)
import UIKit
#endif

// ---------------------------------------------------------------------------
// 图片落盘、压缩、缩略图解码与缓存。
//
// 来源：Android `util/MediaStorage.kt`。做的事情一模一样：
//
//   相册选图 → 按显示尺寸解码（不当整图常驻）→ 长边限制 + JPEG 质量 → 写进沙盒
//   → `messages.attachmentsJson` / `personas.avatarUri` 只存 `file://` 路径。
//
// 与 Android 的差别（iOS 侧更严一点，规格要求）：
// - 聊天图长边 ≤ 1600px、JPEG 质量 0.85、体积上限 ~800KB（Android 是 1568 / 88 / 4MB）；
// - 头像长边 ≤ 512px、JPEG 质量 0.82、体积上限 ~400KB；
// - 任何一次解码都走 `CGImageSourceCreateThumbnailAtIndex`（按最大像素尺寸解码），
//   解码结果进 `NSCache`，上限 = min(物理内存 × 10%, 48MB)，收到内存警告就清空。
//
// 为什么把「参数」与「IO」分开：`ImageEncodingPolicy` 全是纯函数，可以在 macOS 上
// `swift test` 直接断言尺寸与压缩档位；真解码/写盘那一层才碰 ImageIO。
// ---------------------------------------------------------------------------

// MARK: - 纯参数

/// 附件种类：聊天气泡里的大图，还是头像（显示尺寸小得多，没必要存 1600px）。
public enum MediaKind: String, CaseIterable, Sendable {
    case chatImage
    case avatar
}

/// 压缩与尺寸策略。全部是纯函数/常量，单测直接调。
public enum ImageEncodingPolicy {

    /// 聊天图长边上限。
    public static let chatMaxLongEdge = 1600
    /// 聊天图 JPEG 质量。
    public static let chatJPEGQuality = 0.85
    /// 聊天图体积上限（约 800KB）。
    public static let chatMaxBytes = 800 * 1024

    /// 头像长边上限。
    public static let avatarMaxLongEdge = 512
    /// 头像 JPEG 质量。
    public static let avatarJPEGQuality = 0.82
    /// 头像体积上限（约 400KB）。
    public static let avatarMaxBytes = 400 * 1024

    /// 备份导入统一用的 JPEG 质量（规范要求 0.85；选图路径仍按上面对应 kind 的质量）。
    public static let backupImportJPEGQuality = 0.85

    /// 选图体积的硬上限：比这个还大就直接告诉用户「换一张小的」。
    public static let maxInputBytes = 20 * 1024 * 1024
    /// 质量阶梯的下限：再降画质就没法看了，宁可让体积略超。
    public static let minimumQuality = 0.55
    /// 每次重试降多少质量。
    public static let qualityStep = 0.10

    public static func longEdge(for kind: MediaKind) -> Int {
        switch kind {
        case .chatImage: return chatMaxLongEdge
        case .avatar: return avatarMaxLongEdge
        }
    }

    public static func jpegQuality(for kind: MediaKind) -> Double {
        switch kind {
        case .chatImage: return chatJPEGQuality
        case .avatar: return avatarJPEGQuality
        }
    }

    public static func byteLimit(for kind: MediaKind) -> Int {
        switch kind {
        case .chatImage: return chatMaxBytes
        case .avatar: return avatarMaxBytes
        }
    }

    /// 等比缩放到「长边 ≤ longEdge」；本来就小就原样返回（**不放大**）。
    /// 任何一边不是正数就原样退回（调用方会把它当坏图处理）。
    public static func fittedSize(width: Int, height: Int, longEdge: Int) -> (width: Int, height: Int) {
        guard width > 0, height > 0, longEdge > 0 else { return (width, height) }
        let longest = max(width, height)
        guard longest > longEdge else { return (width, height) }
        let scale = Double(longEdge) / Double(longest)
        let fittedWidth = max(1, Int((Double(width) * scale).rounded()))
        let fittedHeight = max(1, Int((Double(height) * scale).rounded()))
        return (fittedWidth, fittedHeight)
    }

    /// 压缩重试阶梯：0.85 → 0.75 → 0.65 → 0.55（到下限为止）。
    /// 传进来的质量本来就低于下限时，就只用它自己（不凭空「变好」）。
    public static func qualityLadder(from quality: Double) -> [Double] {
        let clamped = min(max(quality, 0), 1)
        var result: [Double] = []
        var current = clamped
        while current >= minimumQuality - 1e-9 {
            result.append((current * 100).rounded() / 100)
            current -= qualityStep
        }
        return result.isEmpty ? [clamped] : result
    }

    /// 选图是不是超过了「连读都不该读进内存」的硬上限。
    public static func isTooLargeInput(_ bytes: Int) -> Bool {
        bytes > maxInputBytes
    }

    /// 写出来的文件是不是还超过这一类附件的体积上限。
    public static func exceedsByteLimit(_ bytes: Int, kind: MediaKind) -> Bool {
        bytes > byteLimit(for: kind)
    }
}

// MARK: - 图片压缩（选图与备份导入共用）

/// 内容哈希：文件名里的「这张图是什么」。
///
/// 为什么用 SHA-256：同一张照片从相册选两次、或同一份 v3 备份接两次，压缩后的字节
/// 完全一致，用哈希就能在不新增表的前提下认出来「已经有这张图了」。
public enum ImageContentHash {

    public static func sha256Hex(_ data: Data) -> String {
        SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
    }

    /// 文件名里用的短前缀（前 16 个十六进制字符，64 bit）。
    public static func shortPrefix(_ data: Data) -> String {
        String(sha256Hex(data).prefix(16))
    }
}

/// 图片压缩：把任意输入（jpeg/png/webp、带透明通道）变成「长边受限 + 白底 + JPEG」。
///
/// 与选图路径的关系：选图以前已经会缩尺寸、按质量阶梯压；这里把「白底」与「体积上限」
/// 抽出来，备份导入（规范要求长边 ≤512/≤1600、JPEG 0.85、透明垫白底）直接复用同一套，
/// 避免两条路径各写一份压缩参数。
public enum ImageCompressor {

    /// 从原始字节压缩。解码不了返回 nil（调用方把它算作坏图，而不是让导入失败）。
    public static func compressedJPEG(
        data: Data,
        kind: MediaKind,
        quality: Double = ImageEncodingPolicy.backupImportJPEGQuality
    ) -> Data? {
        guard !data.isEmpty,
              let source = CGImageSourceCreateWithData(data as CFData, nil),
              let decoded = downscaledImage(source: source, maxPixelSize: ImageEncodingPolicy.longEdge(for: kind))
        else { return nil }
        return compressedJPEG(image: decoded, kind: kind, quality: quality)
    }

    /// 从已经解码的图压缩（选图路径手上就是一张缩略图）。
    ///
    /// 两条约束同时成立：**长边不超过该 kind 的上限**、**字节数不超过该 kind 的预算**。
    /// 极端噪点图在目标长边 + 最低质量下仍超预算时，会再等比缩一档尺寸（1200×800 这种），
    /// 而不是把一张超预算的图原样落盘 —— 备份/网盘同步对「单张 800KB」更敏感。
    public static func compressedJPEG(
        image: CGImage,
        kind: MediaKind,
        quality: Double
    ) -> Data? {
        var current = image
        var maxEdge = ImageEncodingPolicy.longEdge(for: kind)
        for _ in 0..<6 {
            if let flattened = flattenedOnWhite(current),
               let encoded = encodedUnderLimit(
                   flattened,
                   quality: quality,
                   byteLimit: ImageEncodingPolicy.byteLimit(for: kind)
               ) {
                return encoded
            }
            // 质量已经降到 0.55 还超：再缩一档尺寸（每档 3/4），直到落进体积上限。
            let nextEdge = max(64, maxEdge * 3 / 4)
            guard nextEdge < maxEdge,
                  let smaller = resized(current, maxPixelSize: nextEdge) else { break }
            current = smaller
            maxEdge = nextEdge
        }
        // 极端图片（几乎全是噪点）：至少给一张压过的图，而不是把这张图丢掉。
        return flattenedOnWhite(current).flatMap {
            encodeJPEG($0, quality: ImageEncodingPolicy.minimumQuality)
        }
    }

    /// 按 ImageIO 的缩略图路径解码（EXIF 方向会被摆正，不把整图读进内存）。
    public static func downscaledImage(source: CGImageSource, maxPixelSize: Int) -> CGImage? {
        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceShouldCacheImmediately: true,
            kCGImageSourceThumbnailMaxPixelSize: max(1, maxPixelSize)
        ]
        return CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary)
    }

    /// 透明垫白底：JPEG 没有 alpha 通道，不垫底的话透明区域会变成黑块（相册里的抠图很常见）。
    public static func flattenedOnWhite(_ image: CGImage) -> CGImage? {
        let width = max(1, image.width)
        let height = max(1, image.height)
        let colorSpace = CGColorSpace(name: CGColorSpace.sRGB) ?? CGColorSpaceCreateDeviceRGB()
        guard let context = CGContext(
            data: nil,
            width: width,
            height: height,
            bitsPerComponent: 8,
            bytesPerRow: 0,
            space: colorSpace,
            bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
        ) else { return nil }
        context.setFillColor(CGColor(red: 1, green: 1, blue: 1, alpha: 1))
        context.fill(CGRect(x: 0, y: 0, width: width, height: height))
        context.draw(image, in: CGRect(x: 0, y: 0, width: width, height: height))
        return context.makeImage()
    }

    /// 等比缩到长边 ≤ maxPixelSize（不放大）。
    public static func resized(_ image: CGImage, maxPixelSize: Int) -> CGImage? {
        let fitted = ImageEncodingPolicy.fittedSize(
            width: image.width, height: image.height, longEdge: maxPixelSize
        )
        guard fitted.width != image.width || fitted.height != image.height else { return image }
        let colorSpace = CGColorSpace(name: CGColorSpace.sRGB) ?? CGColorSpaceCreateDeviceRGB()
        guard let context = CGContext(
            data: nil,
            width: fitted.width,
            height: fitted.height,
            bitsPerComponent: 8,
            bytesPerRow: 0,
            space: colorSpace,
            bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
        ) else { return nil }
        context.interpolationQuality = .high
        context.draw(image, in: CGRect(x: 0, y: 0, width: fitted.width, height: fitted.height))
        return context.makeImage()
    }

    /// 0.85 → 0.75 → 0.65 → 0.55 的质量阶梯，取第一个落进体积上限的；
    /// 都超就返回 nil，让调用方再缩一档尺寸（而不是把超限的图原样落盘）。
    public static func encodedUnderLimit(_ image: CGImage, quality: Double, byteLimit: Int) -> Data? {
        for candidate in ImageEncodingPolicy.qualityLadder(from: quality) {
            guard let encoded = encodeJPEG(image, quality: candidate) else { continue }
            if encoded.count <= byteLimit { return encoded }
        }
        return nil
    }

    /// JPEG 编码（与 MediaStorage 原来那份逐字相同，只是抽成静态函数给两条路径共用）。
    public static func encodeJPEG(_ image: CGImage, quality: Double) -> Data? {
        let data = NSMutableData()
        guard let destination = CGImageDestinationCreateWithData(
            data as CFMutableData, "public.jpeg" as CFString, 1, nil
        ) else { return nil }
        let properties: [CFString: Any] = [
            kCGImageDestinationLossyCompressionQuality: quality
        ]
        CGImageDestinationAddImage(destination, image, properties as CFDictionary)
        guard CGImageDestinationFinalize(destination) else { return nil }
        return data as Data
    }
}

/// 附件文件名的内容哈希前缀（与 `BackupMediaTransfer.newFileName` 的落盘规则同源）。
///
/// 结构：`<kind 前缀><sha256 前 16 位>-<uuid>.<ext>`，例如
/// `chatImage-3f2a9c0d1e4b5a69-1D9E….jpg`。UUID 保证「同名不覆盖」，哈希前缀负责去重。
public enum MediaFileNaming {

    public static let hashPrefixLength = 16

    public static func fileName(kind: MediaKind, contentHash: String, uuid: UUID = UUID()) -> String {
        "\(kind.rawValue)-\(contentHash)-\(uuid.uuidString).jpg"
    }

    public static func fileName(kind: BackupMediaKind, contentHash: String, uuid: UUID = UUID()) -> String {
        "\(kind.fileNamePrefix)\(contentHash)-\(uuid.uuidString).jpg"
    }

    /// 目录里是否已经有一张**同样内容**的图（文件名前缀 = kind + 哈希）。
    public static func existingFile(
        kind: MediaKind,
        contentHash: String,
        in directory: URL,
        fileManager: FileManager = .default
    ) -> URL? {
        existingFile(prefix: "\(kind.rawValue)-\(contentHash)-", in: directory, fileManager: fileManager)
    }

    public static func existingFile(
        kind: BackupMediaKind,
        contentHash: String,
        in directory: URL,
        fileManager: FileManager = .default
    ) -> URL? {
        existingFile(prefix: "\(kind.fileNamePrefix)\(contentHash)-", in: directory, fileManager: fileManager)
    }

    static func existingFile(
        prefix: String,
        in directory: URL,
        fileManager: FileManager = .default
    ) -> URL? {
        guard let items = try? fileManager.contentsOfDirectory(
            at: directory, includingPropertiesForKeys: [.isRegularFileKey]
        ) else { return nil }
        guard let match = items.first(where: { url in
            url.lastPathComponent.hasPrefix(prefix)
                && (try? url.resourceValues(forKeys: [.isRegularFileKey]))?.isRegularFile == true
        }) else { return nil }
        // 用**逻辑目录**拼回路径，而不是枚举出来的 URL：macOS 的临时目录是
        // `/var` → `/private/var` 的符号链接，枚举会返回解析后的路径，和第一次写入用的
        // `/var/...` 长得不一样 —— 两者指向同一个文件，但字符串比较会失败，
        // 而 `attachmentsJson` 里存的 uri 会来回抖动。统一成目录的逻辑路径。
        return directory.standardizedFileURL
            .appendingPathComponent(match.lastPathComponent, isDirectory: false)
            .standardizedFileURL
    }
}

/// 内存预算：缩略图缓存能占多少。
///
/// 为什么按比例而不是写死 48MB：小内存设备（3GB）上 48MB 会明显挤压聊天本身；
/// 大内存设备（8GB）上又太抠。`min(内存 × 10%, 48MB)` 是两层保险。
public enum MemoryBudget {

    /// 缓存最多占物理内存的十分之一。
    public static let cacheFraction = 0.10
    /// 缓存上限的绝对值。
    public static let cacheCapBytes = 48 * 1024 * 1024

    public static func thumbnailCacheLimitBytes(physicalMemory: UInt64) -> Int {
        let tenPercent = Double(physicalMemory) * cacheFraction
        let capped = min(tenPercent, Double(cacheCapBytes))
        return max(1, Int(capped))
    }

    /// 一张解码后的位图占多少（4 字节/像素，RGBA）。
    public static func imageCostBytes(width: Int, height: Int) -> Int {
        max(1, width) * max(1, height) * 4
    }
}

// MARK: - 缩略图解码

/// 按「要显示多大」解码，而不是先把整图读进内存再缩放。
public enum ImageThumbnailDecoder {

    /// 从文件解一张长边 ≤ `maxPixelSize` 的图（EXIF 方向会被摆正）。
    ///
    /// 关键在 `kCGImageSourceCreateThumbnailFromImageAlways` + `ThumbnailMaxPixelSize`：
    /// ImageIO 在解码阶段就直接出缩略图，不会先解一张 1200 万像素的原图。
    public static func thumbnail(url: URL, maxPixelSize: Int) -> CGImage? {
        guard maxPixelSize > 0 else { return nil }
        // 只用文件路径：不把整张图读成 Data（那等于把原图常驻一次）。
        guard let source = CGImageSourceCreateWithURL(url as CFURL, nil) else { return nil }
        return thumbnail(source: source, maxPixelSize: maxPixelSize)
    }

    public static func thumbnail(data: Data, maxPixelSize: Int) -> CGImage? {
        guard maxPixelSize > 0, !data.isEmpty else { return nil }
        guard let source = CGImageSourceCreateWithData(data as CFData, nil) else { return nil }
        return thumbnail(source: source, maxPixelSize: maxPixelSize)
    }

    private static func thumbnail(source: CGImageSource, maxPixelSize: Int) -> CGImage? {
        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceShouldCacheImmediately: true,
            kCGImageSourceThumbnailMaxPixelSize: maxPixelSize
        ]
        return CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary)
    }

    /// 原图像素尺寸（只看元数据，不解码像素）。
    public static func pixelSize(url: URL) -> (width: Int, height: Int)? {
        guard let source = CGImageSourceCreateWithURL(url as CFURL, nil) else { return nil }
        return pixelSize(source: source)
    }

    /// 这份字节的**真实**格式是不是 JPEG（不看备份里声明的 mime：外部输入可能写错）。
    ///
    /// 只有真 JPEG 才可能「保留原图」：PNG/WebP 可能带透明通道，必须重压并垫白底。
    public static func isJPEG(data: Data) -> Bool {
        guard !data.isEmpty, let source = CGImageSourceCreateWithData(data as CFData, nil),
              let type = CGImageSourceGetType(source) else { return false }
        return (type as String) == "public.jpeg"
    }

    /// 原始字节的像素尺寸（只看元数据，不解码像素）：备份导入判断「原图是否已满足长边上限」用。
    public static func pixelSize(data: Data) -> (width: Int, height: Int)? {
        guard !data.isEmpty, let source = CGImageSourceCreateWithData(data as CFData, nil) else {
            return nil
        }
        return pixelSize(source: source)
    }

    private static func pixelSize(source: CGImageSource) -> (width: Int, height: Int)? {
        guard let properties = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any]
        else { return nil }
        let width = (properties[kCGImagePropertyPixelWidth] as? NSNumber)?.intValue ?? 0
        let height = (properties[kCGImagePropertyPixelHeight] as? NSNumber)?.intValue ?? 0
        guard width > 0, height > 0 else { return nil }
        return (width, height)
    }
}

// MARK: - 缩略图缓存

/// 解码后的缩略图缓存。
///
/// `NSCache` 自己会在系统吃紧时回收，但我们仍然显式监听内存警告并清空：
/// 聊天页一屏十几张图，系统回收是滞后的，用户切回来那一下的卡顿是能感觉到的。
public final class ImageThumbnailCache: NSObject {

    public static let shared = ImageThumbnailCache()

    private final class Box {
        let image: CGImage
        init(_ image: CGImage) { self.image = image }
    }

    private let cache = NSCache<NSString, Box>()

    /// 收到内存警告时的回调（App 层在 Debug 里接一条日志，Release 里不接）。
    public var onMemoryWarning: (() -> Void)?

    public init(totalCostLimit: Int = MemoryBudget.thumbnailCacheLimitBytes(
        physicalMemory: ProcessInfo.processInfo.physicalMemory
    )) {
        super.init()
        cache.totalCostLimit = totalCostLimit
        #if canImport(UIKit)
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handleMemoryWarning),
            name: UIApplication.didReceiveMemoryWarningNotification,
            object: nil
        )
        #endif
    }

    public var totalCostLimit: Int {
        get { cache.totalCostLimit }
        set { cache.totalCostLimit = newValue }
    }

    public var countLimit: Int {
        get { cache.countLimit }
        set { cache.countLimit = newValue }
    }

    public func image(forKey key: String) -> CGImage? {
        cache.object(forKey: key as NSString)?.image
    }

    public func store(_ image: CGImage, forKey key: String) {
        let cost = MemoryBudget.imageCostBytes(width: image.width, height: image.height)
        cache.setObject(Box(image), forKey: key as NSString, cost: cost)
    }

    public func removeAll() {
        cache.removeAllObjects()
    }

    #if canImport(UIKit)
    @objc private func handleMemoryWarning() {
        removeAll()
        onMemoryWarning?()
    }
    #endif
}

// MARK: - 路径解析

/// `attachmentsJson` 里的 `uri` → 当前沙盒里的真实文件。
///
/// 为什么不能「拿到路径就直接开文件」：iOS 的 App 数据容器路径里带一段 UUID
/// （`.../Application/<UUID>/Library/Application Support/attachments/...`）。
/// 重装、从备份迁移、某些更新之后这段 UUID 会变，数据库里的绝对路径全部指向
/// 一个已经不存在的目录 —— 图片就会集体变成「图片已不在本机」。
///
/// 所以这里的规则是：
/// 1. `file://` 且文件还在 → 直接用；
/// 2. `file://` 但文件不在 → 用**同名文件**在当前附件目录里找回来（容器换了、图还在）；
/// 3. 没有 scheme 的相对路径 → 按当前附件目录解（防御手工数据）；
/// 4. `http(s)://` / `content://` 之类 → 不是本机文件，返回 nil。
public enum AttachmentLocation {

    /// 目录归属判定（与 Android `MediaOwnership.isWithin` 等价）。
    ///
    /// 三条规则：① 标准化后必须**是目录的子路径**（不是「同前缀」——`attachments2/` 不算）；
    /// ② 解开符号链接之后再比一次（防止附件目录里放一个指向沙盒外的软链）；
    /// ③ 目录自身不算「目录里的文件」。
    public static func isWithin(_ directory: URL, _ candidate: URL) -> Bool {
        let directoryPath = directory.standardizedFileURL.path
        let path = candidate.standardizedFileURL.path
        guard path != directoryPath else { return false }
        let prefix = directoryPath.hasSuffix("/") ? directoryPath : directoryPath + "/"
        guard path.hasPrefix(prefix) else { return false }

        // canonical：软链解开后再比一次（`resolvingSymlinksInPath` 对不存在的路径原样返回）。
        let canonicalDirectory = directory.resolvingSymlinksInPath().standardizedFileURL.path
        let canonicalPath = candidate.resolvingSymlinksInPath().standardizedFileURL.path
        guard canonicalPath != canonicalDirectory else { return false }
        let canonicalPrefix = canonicalDirectory.hasSuffix("/")
            ? canonicalDirectory
            : canonicalDirectory + "/"
        return canonicalPath.hasPrefix(canonicalPrefix)
    }

    /// 原始字符串里有没有路径穿越（按 `/` 与 `\\` 切片，先做百分号解码）。
    ///
    /// 0.11.1 安全审计 P2-3/P2-4：`../../yap.db` 以前会被当成「附件目录下的文件」，
    /// 于是 `exists()` 说它还在、`delete()` 真的把它删了。这条写在解析入口，一处收口。
    public static func hasPathTraversal(_ raw: String) -> Bool {
        let decoded = raw.removingPercentEncoding ?? raw
        let separators = CharacterSet(charactersIn: "/\\")
        return decoded.components(separatedBy: separators).contains("..")
    }

    public static func resolve(
        _ raw: String,
        directory: URL,
        fileExists: (String) -> Bool = { FileManager.default.fileExists(atPath: $0) }
    ) -> URL? {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return nil }
        // 路径穿越一律拒绝：这是「数据即代码」的入口，`..` 不该被解释成语义。
        guard !hasPathTraversal(trimmed) else { return nil }

        guard let url = URL(string: trimmed) else { return nil }
        if let scheme = url.scheme?.lowercased(), scheme != "file" {
            return nil
        }

        if url.isFileURL {
            // 绝对路径：只有**落在当前附件目录里**才直接用（容器没变的情形）。
            // 旧容器路径（UUID 变了）或沙盒外路径都走下面的「同名文件找回来」分支；
            // 沙盒外的路径即便存在，也会因为同名文件不在附件目录里而返回 nil。
            if !hasPathTraversal(url.path), fileExists(url.path), isWithin(directory, url) {
                return url
            }
            let name = url.lastPathComponent
            guard !name.isEmpty, name != ".", name != ".." else { return nil }
            let candidate = directory.appendingPathComponent(name).standardizedFileURL
            guard isWithin(directory, candidate) else { return nil }
            return fileExists(candidate.path) ? candidate : nil
        }

        // 没有 scheme：当作相对当前附件目录的路径（防御手工数据）。
        guard !url.path.isEmpty, !url.path.hasPrefix("/") else { return nil }
        let candidate = directory.appendingPathComponent(url.path).standardizedFileURL
        guard isWithin(directory, candidate) else { return nil }
        return fileExists(candidate.path) ? candidate : nil
    }
}

// MARK: - 备份里的头像路径（与 Android `BackupAvatarPath` 等价）

/// 备份 JSON 里的 `avatarUri` → 能不能落库、落成什么。
///
/// Android 侧（0.10.2 安全审计 P2-2）先拒绝含 `..` 的整条路径、只取最后一段、
/// 再过 `isSafeName` 与 `MediaOwnership.isWithin`。iOS 以前只判断「文件存不存在」，
/// 于是 `../../yap.db` 这种值会被原样写进 `personas.avatarUri`。
public enum BackupAvatarPath {

    /// 名字最长多少（比任何我们自己写的 `avatar-<uuid>.jpg` 都长得多，只用来挡垃圾）。
    public static let maxNameBytes = 128

    /// 只取裸文件名；不安全就返回 nil。
    ///
    /// 拒绝：空串、`..`（路径穿越）、反斜杠、冒号（Windows 盘符/URL scheme）、
    /// `.`/`..`、超长、以及任何带路径分隔符的「名字」。
    public static func fileName(_ raw: String?) -> String? {
        guard let trimmed = raw?.trimmingCharacters(in: .whitespacesAndNewlines), !trimmed.isEmpty else {
            return nil
        }
        let decoded = trimmed.removingPercentEncoding ?? trimmed
        guard !self.hasPathTraversal(decoded) else { return nil }
        if decoded.contains("\\") { return nil }

        // 有 scheme（file:// 等）或绝对路径：取最后一段；裸文件名原样用。
        let name: String
        if decoded.contains("/") {
            name = (decoded as NSString).lastPathComponent
        } else if let url = URL(string: decoded), url.isFileURL {
            name = url.lastPathComponent
        } else {
            name = decoded
        }
        guard !name.isEmpty, name != ".", name != "..", !name.contains("/") else { return nil }
        guard !name.contains(":") else { return nil }
        guard name.utf8.count <= maxNameBytes else { return nil }
        return name
    }

    /// 导出用：只留「我们自己的头像文件名」，其它一律 nil。
    ///
    /// 0.11.1：iOS 导出本来就把绝对路径置空（`exportedAvatarUri`）。这里再补一条：
    /// 只有 `avatar-` 开头的裸文件名才算我们的资源，避免把 `/etc/passwd` 之类导出成 `"passwd"`。
    public static func portable(_ raw: String?) -> String? {
        guard let name = fileName(raw) else { return nil }
        let prefix = MediaKind.avatar.rawValue + "-"
        return name.hasPrefix(prefix) ? name : nil
    }

    /// 导入用：解析成当前附件目录里的绝对 URL 字符串；不存在/不安全就 nil。
    public static func resolveImported(
        _ raw: String?,
        directory: URL,
        fileExists: (String) -> Bool
    ) -> String? {
        guard let name = fileName(raw) else { return nil }
        let candidate = directory.appendingPathComponent(name).standardizedFileURL
        guard AttachmentLocation.isWithin(directory, candidate) else { return nil }
        guard fileExists(candidate.path) else { return nil }
        return candidate.absoluteString
    }

    private static func hasPathTraversal(_ text: String) -> Bool {
        AttachmentLocation.hasPathTraversal(text)
    }
}

// MARK: - 备份里的媒体（v3：图片二进制跟着备份走）

/// 备份 JSON 里 `media[].kind` 的三个取值。
///
/// 为什么是 String 而不是 Swift 枚举：Android 侧同样是字符串常量，而「不认识的 kind」
/// 必须能被导入侧**跳过并计数**，而不是把整条记录当成坏 JSON 丢掉 —— 枚举会强迫
/// 解码器在类型层面就失败。
public enum BackupMediaKind: String, CaseIterable, Sendable {
    case personaAvatar = "PERSONA_AVATAR"
    case userAvatar = "USER_AVATAR"
    case chatImage = "CHAT_IMAGE"
    // MARK: v6（0.13）：表情 / 语音 / 视频也一起带走
    //
    // 与 Android `BackupCodec` 的 `MediaKind` 逐字对应。老版本（v1–v5）读到这三个 kind
    // 会当成「不认识的 kind」跳过并计数 —— 这正是 v6 的兼容方式：旧 App 接新备份只会
    // 少三样附件，人格、记忆、聊天一句不丢。
    case sticker = "STICKER"
    case audio = "AUDIO"
    case video = "VIDEO"

    /// 落盘文件名前缀：头像 `avatar-`、聊天图 `chatImage-`（与 `MediaKind.rawValue` 同源，
    /// `MediaStorage.deleteAllChatImages` 就是按这个前缀清图的）。
    ///
    /// v6 的三类沿用各自目录里的既有前缀（`sticker-` / `audio-` / `video-`）：这样
    /// 导入后文件名与本地选图/录音产生的文件长得一样，清理逻辑不必区分「备份来的」。
    public var fileNamePrefix: String {
        switch self {
        case .personaAvatar, .userAvatar: return MediaKind.avatar.rawValue + "-"
        case .chatImage: return MediaKind.chatImage.rawValue + "-"
        case .sticker: return "sticker-"
        case .audio: return AudioVideoLimits.Kind.audio.fileNamePrefix
        case .video: return AudioVideoLimits.Kind.video.fileNamePrefix
        }
    }

    /// 备份里的类型 → 图片压缩策略（头像 512、聊天图 1600）。
    ///
    /// v6 的三类返回 nil：表情必须保留 GIF 动图（重压会把它压成一张静态 JPEG），
    /// 语音/视频规格里明确「不转码」。它们走「原样写盘」那条路。
    public var imageKind: MediaKind? {
        switch self {
        case .personaAvatar, .userAvatar: return .avatar
        case .chatImage: return .chatImage
        case .sticker, .audio, .video: return nil
        }
    }

    /// 落到哪个本机目录。
    ///
    /// 图片（头像/聊天图）→ `attachments`；表情 → `stickers`；语音/视频 → `audio` / `video`。
    /// 目录本身就是「这条媒体属于谁」的一部分：播放器要的是带扩展名的真实文件，
    /// 渲染层也按目录决定去哪取（`MediaStorage` / `StickerStorage` / `AudioVideoStore`）。
    public var directoryRole: BackupMediaDirectory {
        switch self {
        case .personaAvatar, .userAvatar, .chatImage: return .attachments
        case .sticker: return .stickers
        case .audio: return .audio
        case .video: return .video
        }
    }

    /// 兼容旧代码：图片类返回对应策略，非图片返回 `.chatImage`（调用方应先看 `imageKind`）。
    var legacyImageKind: MediaKind { imageKind ?? .chatImage }
}

/// 备份媒体落在哪个本机目录（导入时按它选目录）。
public enum BackupMediaDirectory: String, Sendable {
    case attachments
    case stickers
    case audio
    case video
}

/// v3 备份里媒体的硬上限（两端同一组数字，改了就是格式变更）。
public enum BackupMediaLimits {

    /// 一份备份里全部媒体的**原始**字节上限：128 MiB（硬上限，实际还会按可用内存收紧）。
    public static let maxTotalBytes = 128 * 1024 * 1024

    /// 当前可用内存（iOS 13+ 用 `os_proc_available_memory`，拿不到时退回物理内存的 1/3）。
    public static func availableMemoryBytes() -> Int {
        #if os(iOS)
        let available = os_proc_available_memory()
        if available > 0 { return available }
        #endif
        return Int(ProcessInfo.processInfo.physicalMemory / 3)
    }

    /// 按可用内存算媒体预算：整份 JSON 在内存里，峰值约为媒体原始字节的 5–6 倍
    /// （base64 ×1.35、字符串副本、解码缓冲）。500×800 KB 在 192 MB 堆上必定 OOM，
    /// 所以预算取 `可用内存 / 12`，再与 [maxTotalBytes] 取小；小堆机型宁可少带几张图。
    public static func effectiveTotalBytes(available: Int = availableMemoryBytes()) -> Int {
        max(4 * 1024 * 1024, min(maxTotalBytes, available / 12))
    }
    /// 单个文件的**原始**字节上限：8 MiB。
    public static let maxFileBytes = 8 * 1024 * 1024
    /// `media[].fileName` 最长多少字节（只用来挡垃圾，比我们自己写的名字宽松得多）。
    public static let maxFileNameBytes = 200

    /// 图片类（头像 / 聊天图）支持的三种格式。别的一律跳过并计数：解码器不认识它们。
    public static let imageMimes: Set<String> = ["image/jpeg", "image/png", "image/webp"]
    /// 表情只收 GIF（表情本来就是 GIF；其它格式到了表情目录也渲染不出来）。
    public static let stickerMimes: Set<String> = ["image/gif"]
    /// 语音（与 `AttachmentsCodec.allowedMimes` / agentd `MediaMime` 同一组值）。
    public static let audioMimes: Set<String> = ["audio/m4a", "audio/mp4", "audio/mpeg"]
    /// 视频（不转码，什么容器就记什么）。
    public static let videoMimes: Set<String> = ["video/mp4", "video/quicktime"]

    /// v3 起就有的名字：图片三种格式。v6 起表示「全部媒体格式的并集」，
    /// 供「不知道 kind 时先粗判一下」的地方用（既有的 `isSupported(mime:)` 依赖它）。
    public static let supportedMimes: Set<String> = imageMimes
        .union(stickerMimes)
        .union(audioMimes)
        .union(videoMimes)

    /// 某个 kind 认哪些 mime（导入/导出两侧共用一张表）。
    public static func supportedMimes(for kind: BackupMediaKind) -> Set<String> {
        switch kind {
        case .personaAvatar, .userAvatar, .chatImage: return imageMimes
        case .sticker: return stickerMimes
        case .audio: return audioMimes
        case .video: return videoMimes
        }
    }

    /// 单张 base64 文本的长度上限（含 padding）：再长就不可能落在单张预算里。
    public static func maxBase64Chars(perFileBytes: Int = maxFileBytes) -> Int {
        ((perFileBytes + 2) / 3) * 4
    }
}

/// 备份媒体的「纯」部分：base64、文件名校验、MIME ⇄ 扩展名、写入受管目录。
///
/// 全部是静态函数、不持有全局状态：单测可以拿临时目录直接喂进来，不碰 `MediaStorage.shared`。
public enum BackupMediaTransfer {

    // MARK: MIME

    public static func isSupported(mime: String) -> Bool {
        BackupMediaLimits.supportedMimes.contains(
            mime.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        )
    }

    public static func isSupported(mime: String, for kind: BackupMediaKind) -> Bool {
        BackupMediaLimits.supportedMimes(for: kind).contains(
            mime.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        )
    }

    /// mime → 消息附件的 `kind`（0.15：导出时把老数据补成显式值）。
    ///
    /// 认不出来时给 `IMAGE`：调用点都是「这条消息原本就在本地显示成图片」的路径，
    /// 猜成图片与 0.14 的行为一致；严格的「未知类型拒绝」发生在 `AttachmentsCodec.decode`。
    public static func attachmentKind(forMime raw: String) -> String {
        MediaType.inferred(fromMime: raw)?.rawValue ?? MessageAttachment.imageKind
    }

    /// MIME → 落盘扩展名。v6 起包含 GIF / 语音 / 视频（视频原样保留容器）。
    public static func fileExtension(forMime mime: String) -> String? {
        switch mime.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() {
        case "image/jpeg": return "jpg"
        case "image/png": return "png"
        case "image/webp": return "webp"
        case "image/gif": return "gif"
        case "audio/m4a", "audio/mp4": return "m4a"
        case "audio/mpeg": return "mp3"
        case "video/mp4": return "mp4"
        case "video/quicktime": return "mov"
        default: return nil
        }
    }

    /// 扩展名 → MIME。猜不到返回 nil（调用方跳过这张图）。
    public static func mime(forFileExtension fileExtension: String) -> String? {
        switch fileExtension.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() {
        case "jpg", "jpeg": return "image/jpeg"
        case "png": return "image/png"
        case "webp": return "image/webp"
        case "gif": return "image/gif"
        case "m4a": return "audio/m4a"
        case "mp3": return "audio/mpeg"
        case "mp4": return "video/mp4"
        case "mov": return "video/quicktime"
        default: return nil
        }
    }

    /// 按文件名后缀猜 MIME；没有后缀 / 不认识的后缀返回 nil。
    public static func mime(forFileName fileName: String) -> String? {
        let name = fileName.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let dot = name.lastIndex(of: "."), dot < name.index(before: name.endIndex) else { return nil }
        return mime(forFileExtension: String(name[name.index(after: dot)...]))
    }

    // MARK: 文件名

    /// 备份里的 `fileName` 是不是一个**裸的、安全的**文件名。
    ///
    /// 校验的是「整条值」而不是它的最后一段：`../../yap.db` 的最后一段看起来无害，
    /// 但整条值绝不能当文件名用。空白、路径分隔符、`..`、超长一律拒绝。
    public static func isSafeFileName(_ raw: String?) -> Bool {
        guard let name = raw?.trimmingCharacters(in: .whitespacesAndNewlines), !name.isEmpty else {
            return false
        }
        guard name.utf8.count <= BackupMediaLimits.maxFileNameBytes else { return false }
        if name.contains("/") || name.contains("\\") { return false }
        if name.contains("..") { return false }
        return true
    }

    /// 导出用：把**本机**引用收成裸文件名。
    ///
    /// 与 `portableFileName` 的区别只有一条：接受绝对路径（表情/语音/视频那一列存的是
    /// 「本机文件路径」，不一定是 `file://` URL）。服务端媒体由调用方先挡掉。
    public static func localFileName(_ raw: String?) -> String? {
        guard let name = BackupAvatarPath.fileName(raw) else { return nil }
        return isSafeFileName(name) ? name : nil
    }

    /// 导出用：把本机 `file:` 路径收成裸文件名（聊天图片用这个；头像用 `BackupAvatarPath.portable`）。
    ///
    /// 规则与 Android `BackupAvatarPath.portable` 一致：只认本地 `file:`、只取最后一段、
    /// 必须是安全名字。**不要求** `chatImage-` 前缀：老数据里可能有别的命名，
    /// 而能不能读字节由「文件真的在附件目录里」决定，不是由名字决定。
    public static func portableFileName(_ raw: String?) -> String? {
        guard let trimmed = raw?.trimmingCharacters(in: .whitespacesAndNewlines), !trimmed.isEmpty else {
            return nil
        }
        guard trimmed.lowercased().hasPrefix("file:") else { return nil }
        guard let name = BackupAvatarPath.fileName(trimmed) else { return nil }
        return isSafeFileName(name) ? name : nil
    }

    /// 导入时的落盘文件名：`avatar-<hash>-<uuid>.<ext>` / `chatImage-<hash>-<uuid>.<ext>`。
    ///
    /// 永远不复用备份里的 `fileName`：备份是外部输入，不能让它决定往哪个路径写字节。
    /// `contentHash` 是压缩后字节的 SHA-256 短前缀（重复导入时据此复用同一张图）。
    public static func newFileName(
        kind: BackupMediaKind,
        mime: String,
        contentHash: String? = nil,
        uuid: UUID = UUID()
    ) -> String? {
        guard let fileExtension = fileExtension(forMime: mime) else { return nil }
        let hashPart = contentHash.map { "\($0)-" } ?? ""
        return "\(kind.fileNamePrefix)\(hashPart)\(uuid.uuidString).\(fileExtension)"
    }

    // MARK: base64

    /// 标准 RFC 4648 base64、带 padding、不换行（`Data.base64EncodedString()` 正是这个）。
    public static func encode(_ data: Data) -> String {
        data.base64EncodedString()
    }

    /// 解码。与 Android `BackupBase64.decode` 同语义：容忍手写排版留下的空白，
    /// 但非法字符、错误的 padding 一律返回 nil（不抛、不崩）。
    public static func decode(_ text: String) -> Data? {
        if text.isEmpty { return Data() }
        var cleaned = ""
        cleaned.reserveCapacity(text.utf8.count)
        for character in text {
            switch character {
            case " ", "\n", "\r", "\t": continue
            default: cleaned.append(character)
            }
        }
        guard cleaned.utf8.count % 4 == 0 else { return nil }
        return Data(base64Encoded: cleaned, options: [])
    }

    // MARK: 写入

    /// 把字节写进受管目录，返回新文件的 URL；任何一步不合规都返回 nil（不抛）。
    ///
    /// 写之前必须过目录归属守卫（`AttachmentLocation.isWithin`）：备份数据只能决定
    /// **文件名**，不能决定路径。
    @discardableResult
    public static func write(
        _ data: Data,
        as fileName: String,
        in directory: URL,
        fileManager: FileManager = .default
    ) -> URL? {
        guard isSafeFileName(fileName) else { return nil }
        let standardizedDirectory = directory.standardizedFileURL
        let target = standardizedDirectory.appendingPathComponent(fileName, isDirectory: false).standardizedFileURL
        guard AttachmentLocation.isWithin(standardizedDirectory, target) else { return nil }
        guard !fileManager.fileExists(atPath: target.path) else { return nil }
        do {
            try fileManager.createDirectory(at: standardizedDirectory, withIntermediateDirectories: true)
            try data.write(to: target, options: .atomic)
            return target
        } catch {
            return nil
        }
    }
}

/// 导出时的一张候选图片：已经被解析到真实的、属于 App 的本地文件。
///
/// `fileName` 是**源文件名**（裸名），它同时会写进 `personas.avatarUri` /
/// `messages.attachmentsJson`，也是导入时「引用 ↔ media」的关联键。
public struct BackupMediaCandidate: Equatable, Sendable {
    public var kind: BackupMediaKind
    public var personaId: String?
    public var fileName: String
    public var fileURL: URL
    public var mime: String

    public init(kind: BackupMediaKind, personaId: String?, fileName: String, fileURL: URL, mime: String) {
        self.kind = kind
        self.personaId = personaId
        self.fileName = fileName
        self.fileURL = fileURL
        self.mime = mime
    }
}

/// 导出时的媒体打包：**只读**文件系统，不写任何东西（单测可直接喂临时目录）。
///
/// 三条规则（与 Android `BackupMediaPacker` 一致）：
/// - 同一张文件（同一路径 + 同一 kind）只装一次；
/// - 单张 > 单张预算：跳过并计入「超限」；
/// - 原始字节累计会超总量：**停止收集**，剩下的（含当前这张）全部计入「超限」——
///   不写半张图，也不写一份自己都导不回来的备份。
public enum BackupMediaPacker {

    public struct Result: Equatable, Sendable {
        public var media: [BackupMedia]
        /// 因为上限没有随备份带走的**份数**（导出后要如实告诉用户）。
        public var skippedByLimit: Int
        /// 这次真的装进 `media` 的聊天图片源文件名：导出侧重写 `attachmentsJson` 只认它们。
        public var chatImageFileNames: Set<String>
        /// v6：消息附件的全部源文件名（图片 + 表情 + 语音 + 视频）。
        ///
        /// 与 `chatImageFileNames` 并存而不是替换：老调用点（备份 fixture 测试、
        /// 只关心图片的断言）继续用前一个；`BackupService` 用这个把
        /// `attachmentsJson` 里四类附件一起降成裸文件名。
        public var attachmentFileNames: Set<String>
        /// v6：表情文件的源文件名（`stickers[].file` 的重写只认它们）。
        public var stickerFileNames: Set<String>

        public init(
            media: [BackupMedia] = [],
            skippedByLimit: Int = 0,
            chatImageFileNames: Set<String> = [],
            attachmentFileNames: Set<String> = [],
            stickerFileNames: Set<String> = []
        ) {
            self.media = media
            self.skippedByLimit = skippedByLimit
            self.chatImageFileNames = chatImageFileNames
            self.attachmentFileNames = attachmentFileNames
            self.stickerFileNames = stickerFileNames
        }
    }

    public static func pack(
        candidates: [BackupMediaCandidate],
        totalBudget: Int = BackupMediaLimits.effectiveTotalBytes(),
        perFileBudget: Int = BackupMediaLimits.maxFileBytes,
        fileManager: FileManager = .default
    ) -> Result {
        // 去重：同一张文件（规范化路径 + kind）只装一次 —— 人格头像与预设头像
        // 完全可能指向同一个文件。
        var seen = Set<String>()
        var unique: [BackupMediaCandidate] = []
        for candidate in candidates {
            let canonical = candidate.fileURL.resolvingSymlinksInPath().standardizedFileURL.path
            let key = "\(candidate.kind.rawValue)|\(canonical)"
            if seen.insert(key).inserted { unique.append(candidate) }
        }
        guard !unique.isEmpty else { return Result() }

        var media: [BackupMedia] = []
        var chatImageFileNames = Set<String>()
        var attachmentFileNames = Set<String>()
        var stickerFileNames = Set<String>()
        var total = 0
        var skipped = 0

        for (position, candidate) in unique.enumerated() {
            guard fileManager.fileExists(atPath: candidate.fileURL.path) else { continue }
            guard BackupMediaTransfer.isSafeFileName(candidate.fileName) else { continue }
            guard BackupMediaTransfer.isSupported(mime: candidate.mime, for: candidate.kind) else { continue }
            let size = (try? candidate.fileURL.resourceValues(forKeys: [.fileSizeKey]))?.fileSize ?? 0
            guard size > 0 else { continue }
            if size > perFileBudget {
                skipped += 1
                continue
            }
            if total + size > totalBudget {
                // 停止收集：剩下的都没有随备份带走（数量按候选清单算，与 Android 同一口径）。
                skipped += unique.count - position
                break
            }
            guard let data = try? Data(contentsOf: candidate.fileURL), !data.isEmpty else { continue }
            total += data.count
            media.append(
                BackupMedia(
                    id: UUID().uuidString,
                    kind: candidate.kind.rawValue,
                    personaId: candidate.personaId,
                    fileName: candidate.fileName,
                    mime: candidate.mime,
                    bytes: data.count,
                    data: BackupMediaTransfer.encode(data),
                    // v7：原始字节的 SHA-256，导入端据此校验「这份字节没在途中被改过」。
                    sha256: ImageContentHash.sha256Hex(data)
                )
            )
            switch candidate.kind {
            case .chatImage:
                chatImageFileNames.insert(candidate.fileName)
                attachmentFileNames.insert(candidate.fileName)
            case .sticker:
                stickerFileNames.insert(candidate.fileName)
                attachmentFileNames.insert(candidate.fileName)
            case .audio, .video:
                attachmentFileNames.insert(candidate.fileName)
            case .personaAvatar, .userAvatar:
                break
            }
        }
        return Result(
            media: media,
            skippedByLimit: skipped,
            chatImageFileNames: chatImageFileNames,
            attachmentFileNames: attachmentFileNames,
            stickerFileNames: stickerFileNames
        )
    }
}

/// 导入时落盘的一张图片：新文件 + 它在备份里的源文件名 + 新的 `file://` 地址。
public struct RestoredBackupMedia: Equatable, Sendable {
    public var kind: BackupMediaKind
    public var fileName: String
    public var fileURL: URL
    public var uri: String
    public var mime: String

    public init(kind: BackupMediaKind, fileName: String, fileURL: URL, uri: String, mime: String) {
        self.kind = kind
        self.fileName = fileName
        self.fileURL = fileURL
        self.uri = uri
        self.mime = mime
    }
}

/// 导入媒体阶段的结果：关联表 + 两种计数 + 这一次真的写下去的文件（失败时用来回滚）。
public struct BackupMediaRestoreResult: Equatable, Sendable {
    public var avatarsByFileName: [String: RestoredBackupMedia] = [:]
    /// 按 `personaId` 再记一份：备份里的人格 `avatarUri` 缺失时用它兜底
    /// （与 Android `BackupMediaRestoreResult.avatarsByPersonaId` 同一个用途）。
    public var avatarsByPersonaId: [String: RestoredBackupMedia] = [:]
    public var chatImagesByFileName: [String: RestoredBackupMedia] = [:]
    public var userAvatar: RestoredBackupMedia?
    /// v6：表情 / 语音 / 视频，键是备份里的裸文件名（表情行 `file` / 附件 `uri` 的关联键）。
    public var stickersByFileName: [String: RestoredBackupMedia] = [:]
    public var audioByFileName: [String: RestoredBackupMedia] = [:]
    public var videoByFileName: [String: RestoredBackupMedia] = [:]
    /// 坏 base64 / 不支持的 mime / 文件名不安全 / 字节数对不上 / 写入失败 / 重复条目。
    public var invalidCount: Int = 0
    /// 声明的字节数、解出来的字节数、或累计总量超出上限。
    public var overLimitCount: Int = 0
    public var writtenFiles: [URL] = []

    public init() {}

    /// 全部「消息附件」的关联表：v6 一条消息可能带图片、表情、语音或视频。
    public var attachmentsByFileName: [String: RestoredBackupMedia] {
        var map = chatImagesByFileName
        for (key, value) in stickersByFileName { map[key] = value }
        for (key, value) in audioByFileName { map[key] = value }
        for (key, value) in videoByFileName { map[key] = value }
        return map
    }

    /// 备份里写到的、但没有落盘成功的条目数（导入后给用户一句「有几样没接回来」）。
    public var missingCount: Int { invalidCount + overLimitCount }
}

/// 导入时的媒体落盘：把 `media[].data` 解码、写进本机对应目录、返回关联表。
///
/// 安全边界（与 Android `BackupMediaRestorer` 一致）：
/// - 备份里的 `fileName` **只做关联键**，不参与拼路径；落盘名一律是新 UUID；
/// - 目标路径必须过 `AttachmentLocation.isWithin` 目录归属守卫；
/// - 坏数据只跳过并计数，绝不抛异常（导入必须成功，哪怕每一份媒体都是坏的）。
///
/// v6 的目录分流：图片（头像/聊天图）→ `directory`（`Application Support/attachments`），
/// 表情 → `stickerDirectory`，语音/视频 → `audioDirectory` / `videoDirectory`。
/// 只有图片会重压；表情保留 GIF 动图、音视频不转码（规格要求）。
public enum BackupMediaRestorer {

    public static func restore(
        entries: [BackupMedia],
        directory: URL,
        stickerDirectory: URL? = nil,
        audioDirectory: URL? = nil,
        videoDirectory: URL? = nil,
        totalBudget: Int = BackupMediaLimits.effectiveTotalBytes(),
        perFileBudget: Int = BackupMediaLimits.maxFileBytes,
        fileManager: FileManager = .default,
        newUUID: () -> UUID = { UUID() }
    ) -> BackupMediaRestoreResult {
        var result = BackupMediaRestoreResult()
        guard !entries.isEmpty else { return result }

        var writtenKeys = Set<String>()
        var total = 0

        for entry in entries {
            let kindRaw = entry.kind.trimmingCharacters(in: .whitespacesAndNewlines)
            guard let kind = BackupMediaKind(rawValue: kindRaw) else {
                result.invalidCount += 1
                continue
            }
            let fileName = entry.fileName.trimmingCharacters(in: .whitespacesAndNewlines)
            guard BackupMediaTransfer.isSafeFileName(fileName) else {
                result.invalidCount += 1
                continue
            }
            let mime = entry.mime.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
            guard BackupMediaTransfer.isSupported(mime: mime),
                  BackupMediaTransfer.isSupported(mime: mime, for: kind),
                  BackupMediaTransfer.fileExtension(forMime: mime) != nil else {
                result.invalidCount += 1
                continue
            }
            // 重复的 (kind, fileName)：只认第一条，其余跳过（同名不同内容是坏数据）。
            let key = "\(kind.rawValue)|\(fileName)"
            guard !writtenKeys.contains(key) else {
                result.invalidCount += 1
                continue
            }
            // 声明的字节数先过一道：明显超限的连 base64 都不用解。
            if entry.bytes < 0 || entry.bytes > perFileBudget
                || entry.data.utf8.count > BackupMediaLimits.maxBase64Chars(perFileBytes: perFileBudget) {
                result.overLimitCount += 1
                continue
            }
            guard let data = BackupMediaTransfer.decode(entry.data),
                  !data.isEmpty,
                  data.count == entry.bytes else {
                result.invalidCount += 1
                continue
            }
            // v7：sha256 非空就校验（老备份没有这个键 → 跳过校验，旧数据照样能接回来）。
            // 不一致的条目按坏数据跳过并计数：绝不把「字节与声明不符」的媒体写进用户目录
            // （那会变成一张能打开但内容不对的图，比读不出来更难查）。
            if let declaredRaw = entry.sha256?.trimmingCharacters(in: .whitespacesAndNewlines),
               !declaredRaw.isEmpty {
                let declared = declaredRaw.lowercased()
                let actual = ImageContentHash.sha256Hex(data)
                guard declared == actual else {
                    result.invalidCount += 1
                    continue
                }
            }
            // 输入闸（规范里的单张 8 MiB / 总量 128 MiB）按**原始字节**算：
            // 超限的图连压缩都不做，避免用一张畸形大图做像素解码。
            if data.count > perFileBudget || total + data.count > totalBudget {
                result.overLimitCount += 1
                continue
            }
            let targetDirectory = targetDirectory(for: kind, attachments: directory,
                                            stickers: stickerDirectory,
                                            audio: audioDirectory,
                                            video: videoDirectory)
            // 目录没给（老调用点只关心图片）：这一条算坏数据，不猜路径。
            guard let targetDirectory else {
                result.invalidCount += 1
                continue
            }
            total += data.count

            let stored: (url: URL, mime: String)?
            if let imageKind = kind.imageKind {
                stored = restoreImage(
                    data: data, kind: kind, imageKind: imageKind,
                    in: targetDirectory, fileManager: fileManager, newUUID: newUUID,
                    writtenFiles: &result.writtenFiles
                )
            } else {
                // 表情 / 语音 / 视频：**原样写盘**（GIF 动图重压会变静图，音视频规格要求不转码）。
                stored = restoreRaw(
                    data: data, kind: kind, mime: mime,
                    in: targetDirectory, fileManager: fileManager, newUUID: newUUID,
                    writtenFiles: &result.writtenFiles
                )
            }
            guard let stored else {
                result.invalidCount += 1
                continue
            }
            writtenKeys.insert(key)
            let restored = RestoredBackupMedia(
                kind: kind, fileName: fileName, fileURL: stored.url,
                uri: stored.url.absoluteString, mime: stored.mime
            )
            switch kind {
            case .personaAvatar:
                result.avatarsByFileName[fileName] = restored
                let personaId = entry.personaId?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
                if !personaId.isEmpty { result.avatarsByPersonaId[personaId] = restored }
            case .userAvatar: result.userAvatar = restored
            case .chatImage: result.chatImagesByFileName[fileName] = restored
            case .sticker: result.stickersByFileName[fileName] = restored
            case .audio: result.audioByFileName[fileName] = restored
            case .video: result.videoByFileName[fileName] = restored
            }
        }
        return result
    }

    /// kind → 落盘目录。图片类在没有 `stickers/audio/video` 参数时也能工作（v3 的老调用点）。
    static func targetDirectory(
        for kind: BackupMediaKind,
        attachments: URL,
        stickers: URL?,
        audio: URL?,
        video: URL?
    ) -> URL? {
        switch kind.directoryRole {
        case .attachments: return attachments
        case .stickers: return stickers
        case .audio: return audio
        case .video: return video
        }
    }

    /// 图片：重压成 JPEG（长边 ≤512/≤1600、透明垫白底），按内容哈希去重。
    private static func restoreImage(
        data: Data,
        kind: BackupMediaKind,
        imageKind: MediaKind,
        in directory: URL,
        fileManager: FileManager,
        newUUID: () -> UUID,
        writtenFiles: inout [URL]
    ) -> (url: URL, mime: String)? {
        // 落盘前压缩：头像长边 ≤512、聊天图 ≤1600，JPEG 0.85、透明垫白底，
        // 体积上限沿用选图路径的 400KB / 800KB。解不出来的图算坏图（跳过并计数）。
        guard let recompressed = ImageCompressor.compressedJPEG(data: data, kind: imageKind) else {
            return nil
        }
        // 大库基准的发现：对本来就更小的低质量 JPEG，重编码反而变大（301KB→547KB）。
        // 只在「原图本来就是 JPEG（无透明通道）、尺寸已满足长边上限、字节已在上限内、
        // 重编码确实更大」时保留原图。PNG/WebP 一律重压：它们可能带 alpha，必须垫白底；
        // 保留原图也不能破坏「导入统一成 JPEG + 透明垫白」这条契约。
        let originalSize = ImageThumbnailDecoder.pixelSize(data: data)
        let originalFitsLongEdge = originalSize.map {
            max($0.width, $0.height) <= ImageEncodingPolicy.longEdge(for: imageKind)
        } ?? false
        let keepOriginal = ImageThumbnailDecoder.isJPEG(data: data)
            && originalFitsLongEdge
            && data.count <= ImageEncodingPolicy.byteLimit(for: imageKind)
            && recompressed.count >= data.count
        let payload = keepOriginal ? data : recompressed
        let compressed = payload
        let contentHash = ImageContentHash.shortPrefix(compressed)

        // 去重（不新增表）：压缩后字节相同 = 同一张图，目录里已有就直接复用那个文件。
        // 文件名里的 SHA-256 前缀就是「有没有这张图」的唯一索引。
        if let existing = MediaFileNaming.existingFile(
            kind: kind, contentHash: contentHash, in: directory, fileManager: fileManager
        ) {
            return (existing, "image/jpeg")
        }
        guard let newName = BackupMediaTransfer.newFileName(
            kind: kind, mime: "image/jpeg", contentHash: contentHash, uuid: newUUID()
        ),
              let written = BackupMediaTransfer.write(
                  compressed, as: newName, in: directory, fileManager: fileManager
              ) else {
            return nil
        }
        writtenFiles.append(written)
        return (written, "image/jpeg")
    }

    /// 表情 / 语音 / 视频：原样写盘，按内容哈希去重（重复导入同一份备份不会翻倍占空间）。
    private static func restoreRaw(
        data: Data,
        kind: BackupMediaKind,
        mime: String,
        in directory: URL,
        fileManager: FileManager,
        newUUID: () -> UUID,
        writtenFiles: inout [URL]
    ) -> (url: URL, mime: String)? {
        let contentHash = ImageContentHash.shortPrefix(data)
        if let existing = MediaFileNaming.existingFile(
            prefix: "\(kind.fileNamePrefix)\(contentHash)-", in: directory, fileManager: fileManager
        ) {
            return (existing, mime)
        }
        guard let newName = BackupMediaTransfer.newFileName(
            kind: kind, mime: mime, contentHash: contentHash, uuid: newUUID()
        ),
              let written = BackupMediaTransfer.write(
                  data, as: newName, in: directory, fileManager: fileManager
              ) else {
            return nil
        }
        writtenFiles.append(written)
        return (written, mime)
    }

    /// `messages.attachmentsJson` 在 v3/v6 里的两条转换。
    ///
    /// 导出：把每条的 `uri` 从设备路径换成**裸文件名**（换机后路径必然失效；裸名用来在
    /// `media` 里找到同一份媒体）。文件不存在 / 名字不安全 / 格式不支持的附件直接丢掉。
    ///
    /// v6 起 `kind` 与 `durationMs` 也要跟着走：语音/视频的时长在服务端不下发，
    /// 备份里丢掉它就只剩一个解不出时长的文件（气泡上永远是 `--:--`）。
    public static func portableAttachments(
        _ raw: String?,
        allowedNames: Set<String>
    ) -> String? {
        let attachments = AttachmentsCodec.decode(raw)
        guard !attachments.isEmpty, !allowedNames.isEmpty else { return nil }
        var portable: [MessageAttachment] = []
        for attachment in attachments {
            guard let name = BackupMediaTransfer.portableFileName(attachment.uri),
                  allowedNames.contains(name) else { continue }
            // mime 可能是历史遗留的怪值：按文件名再认一次，认不出就不带（导入端也解不了）。
            guard BackupMediaTransfer.isSupported(mime: attachment.mime)
                || BackupMediaTransfer.mime(forFileName: name) != nil else { continue }
            let mime = BackupMediaTransfer.isSupported(mime: attachment.mime)
                ? attachment.mime
                : (BackupMediaTransfer.mime(forFileName: name) ?? attachment.mime)
            portable.append(
                MessageAttachment(
                    uri: name, mime: mime,
                    width: attachment.width, height: attachment.height,
                    // 0.15：导出时补上显式 kind（老数据没写 kind 也按 mime 推出来），
                    // 另一端读到的就不再是「猜」。
                    kind: attachment.resolvedMediaType?.rawValue
                        ?? BackupMediaTransfer.attachmentKind(forMime: mime),
                    durationMs: attachment.durationMs,
                    // 0.15：转写跟着附件走（它描述的是「这句话是什么」，不是设备路径）。
                    // 失败标记也一起带走：换机之后气泡上仍然显示「没有转写」，而不是假装没试过。
                    transcript: attachment.transcript,
                    transcriptState: attachment.transcriptState
                )
            )
        }
        return AttachmentsCodec.encode(portable)
    }

    /// 导入：按 `media` 重建本机路径；找不到的附件丢掉（v1/v2 的老行为）。
    ///
    /// - Parameters:
    ///   - restored: v6 的关联表（图片/表情/语音/视频一起）。老调用点仍可用
    ///     `chatImagesByFileName` 那个重载。
    /// - Returns: 新的 `attachmentsJson`；null 表示这条消息没有（可用的）附件了。
    public static func restoredAttachments(
        _ raw: String?,
        chatImagesByFileName: [String: RestoredBackupMedia]
    ) -> String? {
        restoredAttachments(raw, restoredByFileName: chatImagesByFileName)
    }

    public static func restoredAttachments(
        _ raw: String?,
        restoredByFileName: [String: RestoredBackupMedia]
    ) -> String? {
        let attachments = AttachmentsCodec.decode(raw)
        guard !attachments.isEmpty else { return nil }
        var rebuilt: [MessageAttachment] = []
        for attachment in attachments {
            // v3/v6 的附件 uri 是裸文件名；v1/v2 里的设备路径一律不认（换机后本来也指不到文件）。
            let bare = attachment.uri.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !bare.isEmpty, !bare.contains("/"), !bare.contains("\\"), !bare.contains("..") else {
                continue
            }
            guard let media = restoredByFileName[bare] else { continue }
            rebuilt.append(
                MessageAttachment(
                    uri: media.uri, mime: media.mime,
                    width: attachment.width, height: attachment.height,
                    kind: attachment.normalizedKind ?? kindHint(for: media.kind),
                    durationMs: attachment.durationMs,
                    transcript: attachment.transcript,
                    transcriptState: attachment.transcriptState
                )
            )
        }
        return AttachmentsCodec.encode(rebuilt)
    }

    /// 媒体种类 → 附件 `kind` 的提示值（老备份没写 `kind` 时补上，界面才画得对）。
    static func kindHint(for kind: BackupMediaKind) -> String? {
        switch kind {
        case .personaAvatar, .userAvatar, .chatImage: return MessageAttachment.imageKind
        case .sticker: return MessageAttachment.stickerKind
        case .audio: return MessageAttachment.audioKind
        case .video: return MessageAttachment.videoKind
        }
    }
}

// MARK: - 沙盒存储

/// 附件文件本体。
///
/// 目录约定：`Application Support/attachments/`（与数据库同级的 App 私有目录）。
/// 文件名带随机后缀：同一张图被选两次不会互相覆盖，删一条消息也不会误删另一条的图。
public final class MediaStorage {

    public enum StorageError: LocalizedError, Equatable {
        case emptyImage
        case tooLarge
        case unreadable
        case writeFailed

        public var errorDescription: String? {
            switch self {
            case .emptyImage: return String(localized: "这张图片是空的")
            case .tooLarge: return String(localized: "图片超过 20 MB，请换一张小一点的")
            case .unreadable: return String(localized: "读不出这张图片，换一张试试")
            case .writeFailed: return String(localized: "图片没能存到本机")
            }
        }
    }

    /// 附件目录。第一次访问时创建。
    public let directory: URL

    private let fileManager: FileManager

    /// 媒体文件写/删的**进程级**互斥锁（0.12 遗留缺陷 2）。
    ///
    /// `fileManager` 的「查引用 → 删文件」与 `saveImage` 的「写文件 → 返回引用」以前是两步，
    /// 并发选图（同一内容哈希 → 同一个文件名）时可能把刚被引用的文件删掉。
    /// 用 `NSRecursiveLock` 而不是串行队列：这些方法都是同步的，调用方（UI/后台）不该被
    /// 迫切换成异步；可重入只是防御，正常路径不会嵌套。
    private static let mediaFileLock = NSRecursiveLock()

    /// 在媒体文件临界区里执行 `body`。生产代码的写/删与测试的锁契约都走这里。
    func withMediaFileLock<T>(_ body: () throws -> T) rethrows -> T {
        Self.mediaFileLock.lock()
        defer { Self.mediaFileLock.unlock() }
        return try body()
    }

    public init(directory: URL, fileManager: FileManager = .default) {
        self.directory = directory
        self.fileManager = fileManager
        try? fileManager.createDirectory(at: directory, withIntermediateDirectories: true)
        applyBackupExclusion()
    }

    /// 把附件目录从 iCloud/iTunes 整机备份里排除。
    ///
    /// 为什么要有这个方法而不是在 `init` 里写一遍就算了：`deleteAll()` 会把目录删掉重建，
    /// 新建出来的目录没有这个标记（0.10.2 审计 P1-8），于是「清空过一次数据之后」，
    /// 几百 MB 的聊天图与头像开始进整机备份 —— 而用户完全看不出来。
    /// 所以 `init` 与 `deleteAll` 都走这里。
    func applyBackupExclusion() {
        // 图片二进制**不进备份**（与 Android 的约定一致）：
        // 应用自己的备份 JSON 本来就不带图；这里再把附件目录从 iCloud 整机备份里排除，
        // 免得「换机恢复」时把几百 MB 的聊天图一起搬过去（那些路径到了新机器也指不到东西）。
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        var target = directory
        try? target.setResourceValues(values)
    }

    /// App 里的默认实例：`Application Support/attachments`。
    public static let shared: MediaStorage = {
        let fallback = FileManager.default.temporaryDirectory
            .appendingPathComponent("yap-attachments", isDirectory: true)
        let base = (try? FileManager.default.url(
            for: .applicationSupportDirectory,
            in: .userDomainMask,
            appropriateFor: nil,
            create: true
        )) ?? fallback
        return MediaStorage(directory: base.appendingPathComponent("attachments", isDirectory: true))
    }()

    // MARK: 写

    /// 把一张相册图片（原始 Data）压好、落盘，返回可直接写进 `attachmentsJson` 的附件。
    public func saveImage(data: Data, kind: MediaKind) throws -> MessageAttachment {
        guard !data.isEmpty else { throw StorageError.emptyImage }
        guard !ImageEncodingPolicy.isTooLargeInput(data.count) else { throw StorageError.tooLarge }
        guard let source = CGImageSourceCreateWithData(data as CFData, nil) else {
            throw StorageError.unreadable
        }
        return try saveImage(source: source, kind: kind)
    }

    /// 同 `saveImage(data:)`，但直接从文件读：相册选图拿到的是临时 URL 时走这条。
    public func saveImage(at url: URL, kind: MediaKind) throws -> MessageAttachment {
        let attributes = try? fileManager.attributesOfItem(atPath: url.path)
        let size = (attributes?[.size] as? NSNumber)?.intValue ?? 0
        guard size > 0 else { throw StorageError.emptyImage }
        guard !ImageEncodingPolicy.isTooLargeInput(size) else { throw StorageError.tooLarge }
        guard let source = CGImageSourceCreateWithURL(url as CFURL, nil) else {
            throw StorageError.unreadable
        }
        return try saveImage(source: source, kind: kind)
    }

    private func saveImage(source: CGImageSource, kind: MediaKind) throws -> MessageAttachment {
        let limit = ImageEncodingPolicy.longEdge(for: kind)
        guard let decoded = ImageCompressor.downscaledImage(source: source, maxPixelSize: limit) else {
            throw StorageError.unreadable
        }
        // 透明垫白底 + 质量阶梯 + 体积上限：与备份导入共用同一个压缩器（两条路径不会漂）。
        guard let data = ImageCompressor.compressedJPEG(
            image: decoded, kind: kind, quality: ImageEncodingPolicy.jpegQuality(for: kind)
        ) else {
            throw StorageError.unreadable
        }
        // 重复选同一张图：压缩后的字节相同 → 复用已有文件（文件名带 SHA-256 前缀）。
        let contentHash = ImageContentHash.shortPrefix(data)
        // 「复用已有文件 / 写新文件 + 返回引用」必须在同一个媒体文件临界区里：
        // 删除路径的「查引用 + 删」也拿这把锁，不能出现「刚查完无引用，这里又把同名文件返回」。
        return try withMediaFileLock {
            if let existing = MediaFileNaming.existingFile(
                kind: kind, contentHash: contentHash, in: directory, fileManager: fileManager
            ) {
                return MessageAttachment(
                    uri: existing.absoluteString,
                    mime: MessageAttachment.defaultMime,
                    width: decoded.width,
                    height: decoded.height,
                    kind: MessageAttachment.imageKind
                )
            }
            let target = directory.appendingPathComponent(
                MediaFileNaming.fileName(kind: kind, contentHash: contentHash), isDirectory: false
            )
            do {
                try fileManager.createDirectory(at: directory, withIntermediateDirectories: true)
                try data.write(to: target, options: .atomic)
            } catch {
                throw StorageError.writeFailed
            }
            return MessageAttachment(
                uri: target.absoluteString,
                mime: MessageAttachment.defaultMime,
                width: decoded.width,
                height: decoded.height,
                kind: MessageAttachment.imageKind
            )
        }
    }

    // MARK: 读

    /// `uri` → 文件 URL。只认本机文件；容器 UUID 变了会用同名文件在当前目录找回来
    /// （见 `AttachmentLocation`）。外部相册地址一律当文件不见了。
    public func fileURL(for uriString: String) -> URL? {
        AttachmentLocation.resolve(uriString, directory: directory) { path in
            fileManager.fileExists(atPath: path)
        }
    }

    public func fileExists(_ attachment: MessageAttachment) -> Bool {
        guard let url = fileURL(for: attachment.uri) else { return false }
        return fileManager.fileExists(atPath: url.path)
    }

    /// 转成 OpenAI 兼容接口能直接用的 data URL；文件不在了返回 nil（界面显示「图片已不在本机」）。
    ///
    /// 只在「最近两条带图消息」上调用（见 `AttachmentsCodec.maxImageMessagesInContext`）：
    /// base64 会让请求体膨胀 4/3，历史里的图不该全带上。
    public func dataURL(for attachment: MessageAttachment) -> String? {
        guard attachment.isImage, let url = fileURL(for: attachment.uri) else { return nil }
        guard let data = try? Data(contentsOf: url), !data.isEmpty else { return nil }
        return "data:\(attachment.mime);base64,\(data.base64EncodedString())"
    }

    /// 按显示尺寸解码缩略图（带缓存）。文件不存在时返回 nil。
    public func thumbnail(for attachment: MessageAttachment, maxPixelSize: Int) -> CGImage? {
        guard let url = fileURL(for: attachment.uri) else { return nil }
        let key = "\(attachment.uri)#\(maxPixelSize)"
        if let cached = ImageThumbnailCache.shared.image(forKey: key) { return cached }
        guard fileManager.fileExists(atPath: url.path) else { return nil }
        guard let image = ImageThumbnailDecoder.thumbnail(url: url, maxPixelSize: maxPixelSize) else {
            return nil
        }
        ImageThumbnailCache.shared.store(image, forKey: key)
        return image
    }

    // MARK: 删

    public func delete(_ attachment: MessageAttachment) {
        // 0.11.1 安全审计 P2-3：只删**自己目录里**的文件。以前这里对 `fileURL(for:)` 的结果
        // 直接 `removeItem`，而解析又会接受 `../../yap.db` —— 删一条消息就能删到沙盒里的
        // 任意文件。`deleteFileIfOwned` 与头像走的是同一个守卫（与 Android `MediaOwnership` 等价）。
        deleteFileIfOwned(attachment.uri)
    }

    public func delete(_ attachments: [MessageAttachment]) {
        for attachment in attachments { delete(attachment) }
    }

    /// 这条 `uri` 现在还能不能指向一个真实文件（备份导入时判断头像是否失效）。
    ///
    /// 与 `resolve` 同一套规则：容器路径变了但同名文件还在附件目录里，也算「还在」。
    public func exists(uriString: String?) -> Bool {
        guard let uriString, !uriString.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            return false
        }
        guard let url = AttachmentLocation.resolve(uriString, directory: directory) else { return false }
        return fileManager.fileExists(atPath: url.path)
    }

    /// 只删「自己目录里」的文件。
    ///
    /// 为什么要有这条：`avatarUri` 可能来自备份/旧版本（指向别的目录），
    /// 换头像时顺手 `removeItem` 一个不属于我们的路径是危险的。
    public func deleteFileIfOwned(_ uriString: String?) {
        guard let uriString, let url = fileURL(for: uriString) else { return }
        // 目录归属（含符号链接）判定收在 `AttachmentLocation.isWithin`，与 Android 的
        // `MediaOwnership.isWithin` 语义一致：子目录才算、同前缀不算、软链解开后再比。
        guard AttachmentLocation.isWithin(directory, url) else { return }
        try? fileManager.removeItem(at: url)
    }

    /// 删掉一条消息里的所有附件（删消息、清空上下文、清空数据时用）。
    public func deleteAttachments(in json: String?) {
        delete(AttachmentsCodec.decode(json))
    }

    // MARK: 删（引用计数守卫）

    /// 删除文件的**唯一安全入口**：文件必须属于附件目录，且删除时已经零引用。
    ///
    /// 为什么必须有它：文件名按内容哈希去重，同一张图可能被多条消息、人格、预设或
    /// 「我的头像」共享。删消息/删人格/换头像时直接删文件，其它引用就变成「图片已不在本机」。
    /// 调用顺序固定为：**先删数据库行 → 再对候选文件逐个调用这里 → 只有零引用才真删。**
    ///
    /// 引用查询失败时**不删**（保守）：宁可留一个孤儿文件，也不能删掉还有引用的图。
    ///
    /// - Returns: 文件确实被删掉了才返回 true。
    @discardableResult
    public func deleteIfUnreferenced(_ uriString: String?, references: MediaReferences) -> Bool {
        // 「解析路径 + 查引用 + 删文件」全部在同一把进程级媒体锁里（见 `mediaFileLock`）：
        // 并发 `saveImage` 对同一内容哈希文件做「写文件 + 返回引用」时，两边不会交错。
        withMediaFileLock {
            guard let uriString,
                  let fileName = MediaReferences.fileName(from: uriString),
                  let url = fileURL(for: uriString),
                  AttachmentLocation.isWithin(directory, url) else { return false }
            // 只删普通文件：即使目录归属判定被绕过，也不能对子目录做递归删除。
            guard (try? url.resourceValues(forKeys: [.isRegularFileKey]))?.isRegularFile == true else {
                return false
            }
            guard let referenced = try? references.isReferenced(
                fileName: fileName, uriString: uriString
            ), referenced == false else { return false }
            return (try? fileManager.removeItem(at: url)) != nil
        }
    }

    /// 单条附件的便捷入口（界面撤掉一张待发送图片时用）。
    @discardableResult
    public func deleteIfUnreferenced(
        _ attachment: MessageAttachment,
        references: MediaReferences
    ) -> Bool {
        deleteIfUnreferenced(attachment.uri, references: references)
    }

    /// 同一批附件里重复引用同一文件时只查/删一次。
    @discardableResult
    public func deleteIfUnreferenced(
        _ attachments: [MessageAttachment],
        references: MediaReferences
    ) -> Int {
        var seen = Set<String>()
        var removed = 0
        for attachment in attachments {
            guard let name = MediaReferences.fileName(from: attachment.uri),
                  seen.insert(name).inserted else { continue }
            if deleteIfUnreferenced(attachment.uri, references: references) { removed += 1 }
        }
        return removed
    }

    /// 删掉一条消息里的附件：仅删除删除后零引用的文件。
    @discardableResult
    public func deleteAttachmentsIfUnreferenced(
        in json: String?,
        references: MediaReferences
    ) -> Int {
        deleteIfUnreferenced(AttachmentsCodec.decode(json), references: references)
    }

    /// 删掉全部聊天图里**已经零引用**的那些（「清空全部聊天与记忆」用）。
    ///
    /// 调用方必须先删掉消息行；这里仍然逐个查引用，是因为聊天图可能与头像共用同一文件
    /// （内容哈希相同），或者删除过程中有单张表失败、留下了引用。
    @discardableResult
    public func deleteAllChatImages(references: MediaReferences) -> Int {
        var removed = 0
        for name in chatImageFileNames() {
            if deleteIfUnreferenced(name, references: references) { removed += 1 }
        }
        return removed
    }

    /// 删掉附件目录里**已经零引用**的全部文件（「删除全部本地数据」用）。
    ///
    /// 与 `deleteAll()` 的区别：这是引用计数版本，必须等数据库行与设置都清完之后再调用。
    @discardableResult
    public func deleteAll(references: MediaReferences) -> Int {
        var removed = 0
        for name in allFileNames() {
            if deleteIfUnreferenced(name, references: references) { removed += 1 }
        }
        return removed
    }

    private func chatImageFileNames() -> [String] {
        let prefix = MediaKind.chatImage.rawValue + "-"
        return allFileNames().filter { $0.hasPrefix(prefix) }
    }

    private func allFileNames() -> [String] {
        guard let items = try? fileManager.contentsOfDirectory(
            at: directory, includingPropertiesForKeys: [.isRegularFileKey]
        ) else { return [] }
        return items.compactMap { url in
            let isFile = (try? url.resourceValues(forKeys: [.isRegularFileKey]))?.isRegularFile
            return isFile == true ? url.lastPathComponent : nil
        }
    }

    /// 删掉全部附件（聊天图 + 头像）。重建目录后**立刻**恢复「不进备份」标记（P1-8）。
    public func deleteAll() {
        try? fileManager.removeItem(at: directory)
        try? fileManager.createDirectory(at: directory, withIntermediateDirectories: true)
        applyBackupExclusion()
    }

    /// 只删聊天图，人格头像留着（「清空全部聊天与记忆」用：人还在，头像不该消失）。
    ///
    /// 文件名的前缀就是 `MediaKind.rawValue`（`chatImage-<uuid>.jpg` / `avatar-<uuid>.jpg`），
    /// 所以这里按前缀删，而不是遍历数据库里的引用 —— 顺便把历史遗留的孤儿图也清掉。
    ///
    /// - Returns: 删掉了几个文件。
    @discardableResult
    public func deleteAllChatImages() -> Int {
        guard let items = try? fileManager.contentsOfDirectory(
            at: directory, includingPropertiesForKeys: nil
        ) else { return 0 }
        var removed = 0
        for url in items where url.lastPathComponent.hasPrefix(MediaKind.chatImage.rawValue + "-") {
            if (try? fileManager.removeItem(at: url)) != nil { removed += 1 }
        }
        return removed
    }

    /// 附件一共占了多少字节（设置页/报告里给个数字用）。
    public func totalBytesOnDisk() -> Int {
        guard let items = try? fileManager.contentsOfDirectory(
            at: directory, includingPropertiesForKeys: [.fileSizeKey]
        ) else { return 0 }
        return items.reduce(0) { partial, url in
            let size = (try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0
            return partial + size
        }
    }
}
