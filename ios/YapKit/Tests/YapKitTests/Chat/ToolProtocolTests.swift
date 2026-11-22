import Foundation
import XCTest
@testable import YapKit

/// 工具协议：代码块解析、未闭合块的安全处理、原生 tool_calls、回灌格式、动作卡片文案、痕迹编解码。
///
/// 这一层的坏数据**一定**会出现（模型不是编译器）：少个花括号、把对象写成字符串、
/// 连写三个块、写到一半被用户按了停止。任何一个都只能「丢掉这一块」，
/// 不能让整段回复消失、更不能把协议块原样念给用户。
final class ToolProtocolTests: XCTestCase {

    // MARK: - 代码块解析

    func testExtractWithoutBlockOnlyTrims() {
        let (body, calls) = ToolProtocol.extract("  今天有点冷\n ")
        XCTAssertEqual(body, "今天有点冷")
        XCTAssertTrue(calls.isEmpty)
    }

    func testExtractSingleBlockRemovesItFromBody() {
        let raw = """
        我查一下。
        ```yap-tool
        {"name":"web_search","arguments":{"query":"今天天气"}}
        ```
        等我一下。
        """
        let (body, calls) = ToolProtocol.extract(raw)
        XCTAssertFalse(body.contains("yap-tool"), "协议块绝不能留在正文里")
        XCTAssertEqual(body, "我查一下。\n\n等我一下。")
        XCTAssertEqual(calls.count, 1)
        XCTAssertEqual(calls[0].name, "web_search")
        XCTAssertEqual(calls[0].id, "call_block_1")
        let args = try? JSONSerialization.jsonObject(with: Data(calls[0].argumentsJson.utf8)) as? [String: Any]
        XCTAssertEqual(args?["query"] as? String, "今天天气")
    }

    func testExtractMultipleBlocks() {
        let raw = """
        ```yap-tool
        {"name":"think","arguments":{"question":"要不要问"}}
        ```
        中间的文字
        ```yap-tool
        {"name":"remember","arguments":{"content":"他怕冷","drawer":"USER","importance":4}}
        ```
        说完了。
        """
        let (body, calls) = ToolProtocol.extract(raw)
        XCTAssertEqual(calls.map(\.name), ["think", "remember"])
        XCTAssertEqual(calls.map(\.id), ["call_block_1", "call_block_2"])
        XCTAssertTrue(body.contains("中间的文字"))
        XCTAssertFalse(body.contains("yap-tool"))
    }

    /// 坏 JSON：块被丢掉，但**仍然从正文里移除**（它本来就是给客户端看的协议块）。
    func testBadJsonBlockIsDroppedButRemovedFromBody() {
        let raw = """
        前面
        ```yap-tool
        {"name":"web_search", "arguments": {"query": "缺个括号"}
        ```
        后面
        """
        let (body, calls) = ToolProtocol.extract(raw)
        XCTAssertTrue(calls.isEmpty)
        XCTAssertFalse(body.contains("yap-tool"))
        XCTAssertTrue(body.contains("前面"))
        XCTAssertTrue(body.contains("后面"))
    }

    /// 没有 name 的块（只有参数）：当成坏块丢掉。
    func testBlockWithoutNameIsDropped() {
        let raw = "```yap-tool\n{\"arguments\":{\"query\":\"x\"}}\n```"
        let (body, calls) = ToolProtocol.extract(raw)
        XCTAssertTrue(calls.isEmpty)
        XCTAssertEqual(body, "")
    }

    /// 宽松别名：`tool` / `args` / `parameters` 都认；arguments 写成字符串也认。
    func testAliasesAndStringArguments() {
        let raw = """
        ```yap-tool
        {"tool":"recall","args":{"query":"那家店","depth":"3"}}
        ```
        ```yap-tool
        {"name":"think","parameters":"{\\"question\\":\\"嗯\\"}"}
        ```
        """
        let (_, calls) = ToolProtocol.extract(raw)
        XCTAssertEqual(calls.map(\.name), ["recall", "think"])
        XCTAssertEqual(calls[0].argument("query"), "那家店")
        XCTAssertEqual(calls[1].argumentsJson, #"{"question":"嗯"}"#)
    }

    /// 语言标记大小写不敏感（模型爱写 `Yap-Tool`）。
    func testBlockLanguageIsCaseInsensitive() {
        let raw = "```Yap-Tool\n{\"name\":\"think\",\"arguments\":{}}\n```"
        let (_, calls) = ToolProtocol.extract(raw)
        XCTAssertEqual(calls.map(\.name), ["think"])
    }

    // MARK: - 流式可见前缀

    /// 流到一半只有一个开头 ```` ```yap-tool ````：从标记处截断，别让协议块露出来。
    func testVisiblePrefixHidesUnclosedBlock() {
        let streaming = "查一下。\n```yap-tool\n{\"name\":\"web_s"
        XCTAssertEqual(ToolProtocol.visiblePrefix(streaming), "查一下。")
    }

    func testVisiblePrefixKeepsPlainTextAndNormalCodeBlocks() {
        XCTAssertEqual(ToolProtocol.visiblePrefix("普通正文"), "普通正文")
        XCTAssertEqual(ToolProtocol.visiblePrefix(""), "")
        XCTAssertEqual(ToolProtocol.visiblePrefix("   "), "")
        XCTAssertEqual(
            ToolProtocol.visiblePrefix("看这个\n```swift\nprint(1)\n```"),
            "看这个\n```swift\nprint(1)\n```",
            "普通代码块不能被当成协议块截掉"
        )
    }

    // MARK: - 原生 tool_calls

    func testParseNativeCallsStandardShape() {
        let raw = """
        [{"id":"call_1","type":"function","function":{"name":"web_search","arguments":"{\\"query\\":\\"天气\\"}"}}]
        """
        let calls = ToolProtocol.parseNativeCalls(raw)
        XCTAssertEqual(calls.count, 1)
        XCTAssertEqual(calls[0].id, "call_1")
        XCTAssertEqual(calls[0].name, "web_search")
        XCTAssertEqual(calls[0].argument("query"), "天气")
    }

    func testParseNativeCallsWrappedAndFlatShapes() {
        let wrapped = ToolProtocol.parseNativeCalls(
            #"{"tool_calls":[{"function":{"name":"think","arguments":{"question":"问问"}}}]}"#
        )
        XCTAssertEqual(wrapped.map(\.name), ["think"])
        XCTAssertEqual(wrapped[0].id, "call_native_1", "没有 id 时自己生成")

        let calls = ToolProtocol.parseNativeCalls(#"{"calls":[{"name":"recall","arguments":{"query":"店"}}]}"#)
        XCTAssertEqual(calls.map(\.name), ["recall"])
    }

    func testParseNativeCallsBadDataReturnsEmpty() {
        XCTAssertTrue(ToolProtocol.parseNativeCalls(nil).isEmpty)
        XCTAssertTrue(ToolProtocol.parseNativeCalls("").isEmpty)
        XCTAssertTrue(ToolProtocol.parseNativeCalls("garbage").isEmpty)
        XCTAssertTrue(ToolProtocol.parseNativeCalls(#"[{"function":{}}]"#).isEmpty)
    }

    // MARK: - 回灌与展示

    private func result(name: String, ok: Bool = true, summary: String = "查了 3 个网页",
                        content: String = "网页正文", error: String? = nil) -> ToolResult {
        ToolResult(
            invocation: ToolInvocation(id: "call_x", name: name,
                                       argumentsJson: #"{"query":"天气","reason":"想改"}"#),
            ok: ok, summary: summary, contentForModel: content, errorMessage: error
        )
    }

    func testRenderResultsForModelShape() {
        let rendered = ToolProtocol.renderResultsForModel([
            result(name: ToolCatalog.webSearch)
        ])
        XCTAssertTrue(rendered.hasPrefix("【工具结果】web_search"))
        XCTAssertTrue(rendered.contains("查询：天气"))
        XCTAssertTrue(rendered.contains("摘要：查了 3 个网页"))
        XCTAssertTrue(rendered.contains("结果：网页正文"))
    }

    func testRenderFailureUsesErrorText() {
        let rendered = ToolProtocol.renderResultsForModel([
            result(name: ToolCatalog.webSearch, ok: false, summary: "没查到", content: "", error: "网络连接中断")
        ])
        XCTAssertTrue(rendered.contains("失败：网络连接中断"))
        XCTAssertFalse(rendered.contains("结果："))
    }

    func testDescribeTitlesMatchAndroid() {
        XCTAssertEqual(ToolProtocol.describe(result(name: "think")).title, "想了想")
        XCTAssertNil(ToolProtocol.describe(result(name: "think", summary: "想了")).subtitle)
        XCTAssertEqual(ToolProtocol.describe(result(name: "web_search")).title, "联网搜索")
        XCTAssertEqual(ToolProtocol.describe(result(name: "web_search")).subtitle, "查了 3 个网页")
        XCTAssertEqual(ToolProtocol.describe(result(name: "web_fetch")).title, "读了网页")
        XCTAssertEqual(ToolProtocol.describe(result(name: "remember")).title, "记住了一件事")
        XCTAssertEqual(ToolProtocol.describe(result(name: "update_persona")).title, "调整了自己的人设")
        XCTAssertEqual(ToolProtocol.describe(result(name: "set_quiet_hours")).title, "安排了自己的作息")
        XCTAssertEqual(ToolProtocol.describe(result(name: "set_mood")).title, "心情变了")
        XCTAssertEqual(ToolProtocol.describe(result(name: "recall")).title, "回想了一下")
        XCTAssertEqual(ToolProtocol.describe(result(name: "unknown_tool")).title, "unknown_tool")
        XCTAssertEqual(ToolProtocol.describe(result(name: "", summary: "")).title, "动作")
        // 失败时优先说原因
        XCTAssertEqual(
            ToolProtocol.describe(result(name: "web_search", ok: false, summary: "x", error: "超时")).subtitle,
            "超时"
        )
    }

    func testToolMessageJsonShape() {
        let json = ToolProtocol.toolMessageJson(result(name: "web_search", content: "正文"))
        let object = try? JSONSerialization.jsonObject(with: Data(json.utf8)) as? [String: Any]
        XCTAssertEqual(object?["role"] as? String, "tool")
        XCTAssertEqual(object?["tool_call_id"] as? String, "call_x")
        XCTAssertEqual(object?["content"] as? String, "正文")
    }

    func testToolMessageJsonFallbackContent() {
        let json = ToolProtocol.toolMessageJson(result(name: "web_search", ok: false, content: "", error: "失败了"))
        let object = try? JSONSerialization.jsonObject(with: Data(json.utf8)) as? [String: Any]
        XCTAssertEqual(object?["content"] as? String, "失败了")
    }

    // MARK: - 能力说明

    func testCapabilityPromptContainsRequiredLines() {
        let prompt = ToolCatalog.capabilityPrompt(webEnabled: true)
        XCTAssertTrue(prompt.hasPrefix(ToolCatalog.capabilitySection), "0.13 的段标题是「# 你能做什么」")
        XCTAssertTrue(prompt.contains("- think(question, rounds?)："))
        XCTAssertTrue(prompt.contains("- web_search(query, count?)："))
        XCTAssertTrue(prompt.contains("- web_fetch(url)："))
        XCTAssertTrue(prompt.contains("- remember(content, drawer, importance)："))
        XCTAssertTrue(prompt.contains("- recall(query, depth?)："))
        // 输出格式（代码块协议 + 回灌后说完）必须在里面。
        XCTAssertTrue(prompt.contains("```yap-tool"))
        // 0.16：回灌之后要说话仍然只能走 send_message（不再「用自己的口吻说完」）。
        XCTAssertTrue(prompt.contains("结果会交回给你；要接着说就再调用 send_message，别把代码块念给对方。"))
        // 1.9：能力清单里必须有「这一轮先不回」这个动作，否则模型不知道它可以沉默。
        XCTAssertTrue(prompt.contains("- defer_reply("))
        // 0.13：日记板也要在清单里（否则模型不知道它能给自己留便条）。
        XCTAssertTrue(prompt.contains("- diary("))
        // 联网关掉时不再勾引他去查，但明确说「别编」。
        let offline = ToolCatalog.capabilityPrompt(webEnabled: false)
        // 注意：代码块示例里永远有 web_search 这个词，所以这里只断言「没有把联网当成可用工具列出来」。
        XCTAssertFalse(offline.contains("- web_search("))
        XCTAssertFalse(offline.contains("- web_fetch("))
        XCTAssertTrue(offline.contains("你现在没有联网能力"))
    }

    /// 0.13 契约：提示词里的工具清单**就是从 `ToolCatalog` 生成的**（不手写第二份）。
    ///
    /// 断言三件事：
    /// 1. 提示词里出现的每个工具名都在 `ToolCatalog.all` 里（不多）；
    /// 2. `ToolCatalog.all` 里的每个工具都在提示词里（不少）；
    /// 3. 每个工具的用途与副作用都真的写进了提示词（加工具必须写清「调用后世界变什么」）。
    func testCapabilityPromptIsGeneratedFromToolCatalog() {
        let prompt = ToolCatalog.capabilityPrompt(webEnabled: true)
        let listed = Self.toolNames(inCapabilityPrompt: prompt)
        XCTAssertEqual(listed, Set(ToolCatalog.all.map(\.name)), "提示词的工具集合必须等于 ToolCatalog.all")
        XCTAssertEqual(listed.count, ToolCatalog.all.count, "不能重复列同一个工具")
        for spec in ToolCatalog.all {
            XCTAssertTrue(prompt.contains("- \(ToolCatalog.signature(spec))："), "缺少工具行：\(spec.name)")
            XCTAssertTrue(prompt.contains(spec.description), "\(spec.name) 的用途没进提示词")
            XCTAssertFalse(spec.sideEffect.isEmpty, "\(spec.name) 必须写清副作用")
            XCTAssertTrue(prompt.contains(spec.sideEffect), "\(spec.name) 的副作用没进提示词")
        }
        // 联网关掉：清单里只剩 9 个（去掉两个联网工具），代码块示例不算。
        let offline = Self.toolNames(inCapabilityPrompt: ToolCatalog.capabilityPrompt(webEnabled: false))
        XCTAssertEqual(offline, Set(ToolCatalog.all.map(\.name)).subtracting([ToolCatalog.webSearch, ToolCatalog.webFetch]))
        // 原生 tools 数组与提示词是同一份集合。
        let json = ToolCatalog.toOpenAiToolsJson(ToolCatalog.enabled(webEnabled: false))
        let array = try? JSONSerialization.jsonObject(with: Data(json.utf8)) as? [[String: Any]]
        let nativeNames = Set(array?.compactMap { ($0["function"] as? [String: Any])?["name"] as? String } ?? [])
        XCTAssertEqual(nativeNames, offline)
    }

    /// 从「- name(...)：」这种行里取出工具名（代码块里的 JSON 不算）。
    static func toolNames(inCapabilityPrompt prompt: String) -> Set<String> {
        var names = Set<String>()
        for line in prompt.split(separator: "\n") {
            guard line.hasPrefix("- ") else { continue }
            guard let open = line.firstIndex(of: "(") else { continue }
            let name = line[line.index(line.startIndex, offsetBy: 2)..<open]
            names.insert(name.trimmingCharacters(in: .whitespaces))
        }
        return names
    }

    func testToolCatalogShape() {
        // 1.9 多了一个 `defer_reply`：模型可以用它说「这一轮先不回」（§四）。
        // 0.12 多了一个 `set_activity`：模型可以声明「我接下来这段时间在做什么」（人设引导 §4）。
        // 0.13 多了一个 `diary`：他自己的日记板（`spec/diary/README.md`）。
        // 0.13 表情包再多一个 `sticker`：发/做/收藏表情（`spec/stickers/README.md`）。
        // 0.13 音视频再多 `listen` / `watch`：真的听一段语音、看几帧视频画面
        //（`spec/audio-video/README.md`；不是转写、不是猜）。
        // 0.13 多维情绪/可以不回再加一个 `stay_silent`：这一轮只标已读、不回
        //（`spec/mood/README.md` §4；与 `defer_reply` 的分工见工具描述）。
        XCTAssertEqual(ToolCatalog.all.count, 18)
        XCTAssertEqual(ToolCatalog.all.map(\.name), [
            "think", "web_search", "web_fetch", "remember",
            "update_persona", "set_quiet_hours", "set_activity", "set_mood", "recall", "memorize",
            "defer_reply", "stay_silent", "diary", "sticker", "listen", "watch",
            // 0.14 体验版：连发与「他自己决定暂时不理对方」
            //（`spec/experience/README.md` §4/§2）。
            "send_message", "block_user"
        ])
        XCTAssertNotNil(ToolCatalog.byName(" recall "))
        XCTAssertNil(ToolCatalog.byName("nope"))
        for spec in ToolCatalog.all {
            let data = Data(spec.parametersJson.utf8)
            XCTAssertNotNil(try? JSONSerialization.jsonObject(with: data),
                            "\(spec.name) 的 parameters 必须是合法 JSON")
            XCTAssertFalse(spec.description.isEmpty)
        }
        // OpenAI tools 数组形状
        let json = ToolCatalog.toOpenAiToolsJson()
        let array = try? JSONSerialization.jsonObject(with: Data(json.utf8)) as? [[String: Any]]
        XCTAssertEqual(array?.count, 18)
        XCTAssertEqual(array?.first?["type"] as? String, "function")
        let function = array?.first?["function"] as? [String: Any]
        XCTAssertEqual(function?["name"] as? String, "think")
        XCTAssertNotNil(function?["parameters"] as? [String: Any])
    }

    // MARK: - set_activity（人设引导 §4）

    /// `set_activity` 的参数形状与契约一致（kind / availability 枚举、minutes 15–480）。
    func testSetActivitySpecMatchesContract() throws {
        let spec = try XCTUnwrap(ToolCatalog.byName("set_activity"))
        let data = try XCTUnwrap(spec.parametersJson.data(using: .utf8))
        let root = try XCTUnwrap(try JSONSerialization.jsonObject(with: data) as? [String: Any])
        let properties = try XCTUnwrap(root["properties"] as? [String: Any])
        XCTAssertNotNil(properties["activity"])
        XCTAssertNotNil(properties["minutes"])
        let kind = try XCTUnwrap(properties["kind"] as? [String: Any])
        XCTAssertEqual(kind["enum"] as? [String], ActivityKind.allCases.map(\.rawValue))
        let availability = try XCTUnwrap(properties["availability"] as? [String: Any])
        XCTAssertEqual(
            availability["enum"] as? [String],
            ActivityAvailability.allCases.map(\.rawValue)
        )
        let minutes = try XCTUnwrap(properties["minutes"] as? [String: Any])
        XCTAssertEqual(minutes["minimum"] as? Int, ToolArgClamp.activityMinutesMin)
        XCTAssertEqual(minutes["maximum"] as? Int, ToolArgClamp.activityMinutesMax)
        // 只有 activity 是必填：模型不该因为不确定类型就不敢调用。
        XCTAssertEqual(root["required"] as? [String], ["activity"])
    }

    /// 工具描述必须写清与 `set_quiet_hours` 的区别（规范 §4 明文要求）。
    func testSetActivityDescriptionExplainsQuietHoursDifference() throws {
        let activity = try XCTUnwrap(ToolCatalog.byName("set_activity"))
        XCTAssertTrue(activity.description.contains("不是睡觉"), activity.description)
        XCTAssertTrue(activity.description.contains("set_quiet_hours"), activity.description)
        XCTAssertTrue(activity.description.contains("两者可以同时存在"), activity.description)
        // 能力提示里也要有这个工具与它的取值范围。
        let prompt = ToolCatalog.capabilityPrompt()
        // 参数提示由 schema 生成：必填在前，其余按字母序（activity, availability?, kind?, minutes?）。
        XCTAssertTrue(prompt.contains("- set_activity(activity, availability?, kind?, minutes?)："))
        XCTAssertTrue(prompt.contains("15–480"))
        XCTAssertTrue(prompt.contains("BUSY/LIMITED/FREE"))
        // 动作卡片标题不裸露工具名。
        let result = ToolResult(
            invocation: ToolInvocation(name: "set_activity", argumentsJson: #"{"activity":"在开会"}"#),
            ok: true, summary: "在开会", contentForModel: ""
        )
        XCTAssertEqual(ToolProtocol.describe(result).title, "去忙了")
    }

    // MARK: - 痕迹

    func testToolTraceEncodeDecodeRoundTrip() throws {
        let traces = [
            ToolTraceItem(name: "web_search", title: "联网搜索", subtitle: "DeepSeek V4 发布",
                          detail: "查询：DeepSeek", ok: true),
            ToolTraceItem(name: "think", title: "想了想", subtitle: nil, detail: "", ok: true)
        ]
        let encoded = try XCTUnwrap(ToolTraceCodec.encode(traces))
        XCTAssertEqual(ToolTraceCodec.decode(encoded), traces)
        // 空痕迹写 nil（不是 "[]"）
        XCTAssertNil(ToolTraceCodec.encode([]))
        XCTAssertEqual(ToolTraceCodec.decode(nil), [])
        XCTAssertEqual(ToolTraceCodec.decode("坏数据"), [])
    }

    /// 字段顺序按声明顺序写（与 Android 的 kotlinx 一致），nil 的 subtitle 直接省略。
    func testToolTraceJsonShape() throws {
        let json = try XCTUnwrap(ToolTraceCodec.encode([
            ToolTraceItem(name: "think", title: "想了想", subtitle: nil, detail: "d", ok: true)
        ]))
        XCTAssertEqual(json, #"[{"name":"think","title":"想了想","detail":"d","ok":true}]"#)
        let withSubtitle = try XCTUnwrap(ToolTraceCodec.encode([
            ToolTraceItem(name: "web_search", title: "联网搜索", subtitle: "标题", detail: "d", ok: false)
        ]))
        XCTAssertEqual(withSubtitle, #"[{"name":"web_search","title":"联网搜索","subtitle":"标题","detail":"d","ok":false}]"#)
        // 转义要正确
        let escaped = try XCTUnwrap(ToolTraceCodec.encode([
            ToolTraceItem(name: "a\"b", title: "c\nd", subtitle: nil, detail: "e\\f", ok: true)
        ]))
        XCTAssertEqual(ToolTraceCodec.decode(escaped).first?.name, "a\"b")
        XCTAssertEqual(ToolTraceCodec.decode(escaped).first?.title, "c\nd")
        XCTAssertEqual(ToolTraceCodec.decode(escaped).first?.detail, "e\\f")
    }

    func testToolTraceSummarize() {
        XCTAssertEqual(ToolTraceCodec.summarize([]), "")
        XCTAssertEqual(
            ToolTraceCodec.summarize([
                ToolTraceItem(name: "web_search", title: "联网搜索"),
                ToolTraceItem(name: "web_search", title: "联网搜索"),
                ToolTraceItem(name: "think", title: "想了想")
            ]),
            "联网搜索 2 次 · 想了想"
        )
        // 没有 title 时退回 name
        XCTAssertEqual(ToolTraceCodec.summarize([ToolTraceItem(name: "recall")]), "recall")
    }

    func testToolTraceFromResult() {
        let trace = ToolTraceItem.from(result(name: "web_search", content: "很长的一段结果"))
        XCTAssertEqual(trace.name, "web_search")
        XCTAssertEqual(trace.title, "联网搜索")
        XCTAssertEqual(trace.subtitle, "查了 3 个网页")
        XCTAssertEqual(trace.detail, "很长的一段结果")
        XCTAssertTrue(trace.ok)

        let failed = ToolTraceItem.from(result(name: "web_search", ok: false, content: "", error: "超时"))
        XCTAssertFalse(failed.ok)
        XCTAssertEqual(failed.detail, "超时")
    }
}
