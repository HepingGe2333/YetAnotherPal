import Foundation

// 来源：app/src/main/java/com/yap/app/data/repo/ToolExecutor.kt
//
// 他手里的工具：查资料、读网页、记事、改自己的人设、安排作息、记心情、翻记忆。
//
// ## 这是唯一执行工具的地方
//
// 聊天流程（`ChatService`）只做三件事：发现调用 → 交给这里 → 把结果回灌给模型。
// 每个工具自己决定该不该拒绝（没开联网、时间格式不对、内容太短……），
// 失败也要给模型一句人话（`contentForModel`），不许静默。
//
// ## 与 Android 的对齐点（照 `ToolExecutor.kt` 逐条搬）
//
// 1. **工具名**：`think` / `web_search` / `web_fetch` / `remember` / `update_persona`（= 人设卡补丁，
//    Android 用这个名字；这里额外容忍 `persona_patch` 这个别名）/ `set_quiet_hours`（含 `dnd`）/
//    `set_mood` / `recall` / `defer_reply`；
// 2. **参数夹取**：`importance` 1–5、`count` 1–12（缺省 6）、`depth` 宽松解析（1/2/3、quick/normal/deep、
//    中文别名都认，认不出按「认真回忆」）、`minutes` 5–720、`reason` 截断长度；
// 3. **回灌给模型的文案形状**：成功是「心情已经记下……」「已写进长期记忆……」，失败是
//    「这次调用没成功：……。用你已经知道的东西继续，不要向对方解释工具细节。」；
// 4. **动作卡片的标题**按结果变（「记住了」/「又确认了一次」/「这次先不记」/「想了想，没有改」），
//    不总等于「记住了一件事」。
//
// ## iOS 与 Android 的已知差异（都写在报告里，不是静默空实现）
//
// - `web_search` / `web_fetch` 走 `Net/WebToolsClient.swift`：0.12 起支持 MESSAGEAPI / Tavily /
//   Brave / Serper / 自建服务五个 provider（规范 `spec/web-search/README.md`）。工具协议与
//   回灌给模型的文本形状不变；没开联网说「这个人格还没有开启联网」，缺 Key 则明确说缺 Key；
// - Android 搜到东西后会另开一次「用完即弃」的打分（`WebSearchScorer`）把值得记的写进长期记忆，
//   iOS 这一步还没接（见报告）——搜索本身不受影响；
// - `persona_revisions` / `persona_pending_patches` 两张表 iOS 没有，所以改人设直接生效、版本号靠
//   `persona.version`（与 1.3 起的 Android 行为一致：不等用户点头）。

public final class ToolExecutor: ToolRunning {

    // MARK: - 依赖

    private let stores: DatabaseStores
    private let apiKeys: ApiKeyStore
    private let memory: MemoryRepository
    private let recallService: RecallService
    private let mood: MoodRepository
    private let personaCards: PersonaCardRepository
    private let intents: AgentIntentSupport
    private let echoes: MemoryEchoStoring
    /// 表情包（`spec/stickers/README.md`）：发 / 做 / 收藏。
    ///
    /// 为什么默认值按 `stores` 现造一个：工具执行器在 Kit 里，不依赖 App 的装配；
    /// 服务端模式需要「上传/下载」闭包，由装配层注入一个配置好的实例（见 `AppEnvironment`）。
    private let stickers: StickerService
    /// 语音/视频（0.13 `listen` / `watch`）：读本机媒体 + 抽视频关键帧。
    ///
    /// 与 `MediaStorage` 分开注入：单测用临时目录 + 假抽帧器，
    /// 不必真的录一段音、解一段视频。
    private let audioVideo: AudioVideoStore
    private let videoFrames: VideoFrameExtracting
    /// 拉黑（0.14）：提议 / 两轮确认 / 到期解除都在这里（`BlockRepository`）。
    private let block: BlockRepository
    private let webTools: WebToolsClient
    /// 搜完顺手判断「值不值得记」；没接（或没配置）就是「只搜不记」，搜索本身不受影响。
    private let scorer: WebSearchScoring?
    /// agent 决策层（0.11.0）：工具参数夹取与 `defer_reply` 计划走它。
    /// 默认本机实现；配成远程时失败会回退本机（工具执行不该因为内核服务没起而失败）。
    private let agentCore: any AgentCoreClient
    /// 服务端模式下把 `set_activity` 的当前活动上报给服务端（M3 写路径的最小闭环）。
    ///
    /// 为什么是闭包而不是直接依赖 `ServerAPIClient`：工具执行器在 Kit 里，
    /// 它不该知道「现在是不是服务端模式」；装配层（`AppEnvironment`）按当时配置决定接不接。
    /// nil = 本地模式（什么都不做，本地那份活动已经写进人格了）。
    private let activityReported: ((PersonaActivity, String) -> Void)?
    private let now: () -> Int64
    private let log: (String) -> Void

    /// 联网工具默认地址（与 Android 的 `DEFAULT_MESSAGEAPI_BASE` 一致）。
    public static let defaultWebToolsBase = "https://api.deepseek.com"

    public init(
        stores: DatabaseStores,
        apiKeys: ApiKeyStore,
        memory: MemoryRepository,
        recallService: RecallService,
        mood: MoodRepository,
        personaCards: PersonaCardRepository,
        intents: AgentIntentSupport,
        echoes: MemoryEchoStoring? = nil,
        stickers: StickerService? = nil,
        audioVideo: AudioVideoStore = .shared,
        videoFrames: VideoFrameExtracting = AVVideoFrameExtractor(),
        block: BlockRepository? = nil,
        webTools: WebToolsClient = WebToolsClient(),
        scorer: WebSearchScoring? = nil,
        agentCore: any AgentCoreClient = AgentCoreClientFactory.makeNative(),
        activityReported: ((PersonaActivity, String) -> Void)? = nil,
        now: @escaping () -> Int64 = { DomainClock.nowMillis },
        log: @escaping (String) -> Void = { _ in }
    ) {
        self.stores = stores
        self.apiKeys = apiKeys
        self.memory = memory
        self.recallService = recallService
        self.mood = mood
        self.personaCards = personaCards
        self.intents = intents
        self.echoes = echoes ?? MemoryEchoStore(database: stores.database)
        self.stickers = stickers ?? StickerService(stores: stores)
        self.audioVideo = audioVideo
        self.videoFrames = videoFrames
        self.block = block ?? BlockRepository(stores: stores, now: now)
        self.webTools = webTools
        self.scorer = scorer
        self.agentCore = agentCore
        self.activityReported = activityReported
        self.now = now
        self.log = log
    }

    // MARK: - 总入口

    public func run(
        _ invocation: ToolInvocation,
        persona: PersonaEntity,
        sessionId: String,
        userMessageId: String? = nil
    ) async -> ToolResult {
        let name = invocation.name.trimmingCharacters(in: .whitespacesAndNewlines)
        let at = now()
        switch name {
        case "web_search":
            return await webSearch(invocation, persona: persona, sessionId: sessionId)
        case "web_fetch":
            return await webFetch(invocation, persona: persona, sessionId: sessionId)
        case "remember":
            return await remember(invocation, persona: persona, sessionId: sessionId, now: at)
        case "update_persona", "persona_patch":
            return updatePersona(invocation, persona: persona, sessionId: sessionId, now: at)
        case "set_quiet_hours":
            return setQuietHours(invocation, persona: persona, now: at)
        case "set_activity":
            return setActivity(invocation, persona: persona, sessionId: sessionId, now: at)
        case "set_mood":
            return setMood(invocation, persona: persona, now: at)
        case "recall":
            return recall(invocation, persona: persona, now: at)
        case "memorize":
            return memorize(invocation, sessionId: sessionId, now: at)
        case "defer_reply":
            return await deferReply(invocation, persona: persona, sessionId: sessionId, now: at)
        case "stay_silent":
            return staySilent(invocation, persona: persona)
        case "diary":
            return diary(invocation, persona: persona, now: at)
        case "think":
            return think(invocation)
        case "sticker":
            return await sticker(invocation, persona: persona, sessionId: sessionId)
        case "listen":
            return await listen(invocation, sessionId: sessionId)
        case "watch":
            return await watch(invocation, sessionId: sessionId)
        case "send_message":
            return sendMessage(invocation)
        case "block_user":
            return blockUser(
                invocation, persona: persona, sessionId: sessionId,
                userMessageId: userMessageId, now: at
            )
        default:
            // 不认识就明确说，不假装做过：模型要能知道这条路走不通。
            return failure(invocation, String(format: String(localized: "不认识这个工具：%@"), name))
        }
    }

    // MARK: - 连发（0.14）

    /// 这一轮要发出去的一句（≤120 字）。
    ///
    /// 只做两件事：清洗 + 截断，然后把正文交给 `ChatService`。
    /// **不落库**的理由写在 `ToolResult.outboundText` 的注释里：节奏只能有一处实现。
    private func sendMessage(_ invocation: ToolInvocation) -> ToolResult {
        guard let text = MessageRhythm.cleanSendText(invocation.argument("text")) else {
            return failure(invocation, String(localized: "没有写要发什么"))
        }
        var result = success(
            invocation,
            title: String(localized: "发了条消息"),
            subtitle: text,
            detail: text,
            // 0.16：messageId 由聊天链路在落库之后回填（`OutboundProtocol.sentReceipt`）；
            // 这里只在「还没落库」的兜底文案里说清下一步，不伪造 id。
            modelText: "这一句已经交给聊天链路发出去了。想接着说下一句就再调用一次 send_message；"
                + "不说了就直接结束这一轮。不要再用正文重复它。"
        )
        result.outboundText = text
        return result
    }

    // MARK: - 拉黑（0.14，`spec/experience/README.md` §2）

    /// 模型想暂时不理对方。
    ///
    /// 与 Android `ToolExecutor.blockUser` 同一套结论与文案（两轮确认、10 分钟过期、
    /// `pending_confirmation` / `blocked` 这两个稳定字符串）：
    /// 回灌给模型的话必须说清「生效没生效」——它很容易把「提议」当成「已经拉黑」，
    /// 然后真的不回消息。
    private func blockUser(
        _ invocation: ToolInvocation,
        persona: PersonaEntity,
        sessionId: String,
        userMessageId: String?,
        now: Int64
    ) -> ToolResult {
        // 过期解除在读取时完成（`state` 顺手清列），这里只需要确认现状。
        let confirm = invocation.boolArgument("confirm") ?? false
        // 模型没写 hours 时按 12 小时；写了 0 / 99 由 `BlockPolicy.hours` 夹到 1–24。
        let requestedHours = invocation.optionalIntArgument("hours").map(BlockPolicy.clampHours)
        let hours = requestedHours ?? BlockPolicy.defaultHours
        let reason = invocation.argument("reason")

        if !confirm {
            let proposal = block.propose(
                personaId: persona.id,
                hours: hours, reason: reason, userMessageId: userMessageId, now: now
            )
            return pendingResult(invocation, proposal: proposal)
        }

        switch block.confirm(
            personaId: persona.id,
            hours: requestedHours, reason: reason, userMessageId: userMessageId, now: now
        ) {
        case .accepted:
            let until = (try? stores.personas.byId(persona.id))?.blockedUntil ?? 0
            let state = block.state(personaId: persona.id, now: now)
            return success(
                invocation,
                title: String(localized: "已拉黑"),
                subtitle: state.map { PluralText.format("拉黑 %d 小时", hoursUntil($0.until, now: now)) }
                    ?? String(localized: "已拉黑"),
                detail: String(localized: "已生效，到期自动解除。"),
                modelText: Self.blockResultJSON(status: "blocked", key: "blockedUntil", value: until)
            )
        case .waitingForNextRound:
            let proposal = block.pendingProposal(personaId: persona.id, now: now)
            return success(
                invocation,
                title: String(localized: "拉黑提议"),
                subtitle: proposal.map { PluralText.format("拉黑 %d 小时", $0.hours) },
                detail: String(localized: "已经把提议记下来了；等对方再发一条消息之后，你才能真正确认。"),
                modelText: "现在还不能确认：中间还没有新的用户消息。等下一轮再说；"
                    + "如果这轮已经说了什么，就正常把话说完。"
            )
        case .expiredProposal:
            return success(
                invocation,
                title: String(localized: "拉黑提议"),
                subtitle: nil,
                detail: String(localized: "已经把提议记下来了；等对方再发一条消息之后，你才能真正确认。"),
                modelText: "那个拉黑提议已经过期了。如果还是想拉黑，先重新提一次（不要 confirm），等下一轮再确认。"
            )
        case .needsProposal:
            return success(
                invocation,
                title: String(localized: "拉黑提议"),
                subtitle: nil,
                detail: String(localized: "已经把提议记下来了；等对方再发一条消息之后，你才能真正确认。"),
                modelText: "还没有可以确认的拉黑提议。先调用一次 block_user（不要 confirm）提交提议。"
            )
        }
    }

    /// 第一次调用（或重复调用）的结论：`pending_confirmation`（规范 §9 的稳定字符串）。
    private func pendingResult(_ invocation: ToolInvocation, proposal: BlockProposal) -> ToolResult {
        success(
            invocation,
            title: String(localized: "拉黑提议"),
            subtitle: PluralText.format("拉黑 %d 小时", proposal.hours),
            detail: String(localized: "已经把提议记下来了；等对方再发一条消息之后，你才能真正确认。"),
            modelText: Self.blockResultJSON(
                status: "pending_confirmation", key: "expiresAt", value: proposal.expiresAt
            )
        )
    }

    /// 工具回灌给模型的统一 JSON（`spec/experience/README.md` §9）：
    /// `{"status":"pending_confirmation","expiresAt":…}` / `{"status":"blocked","blockedUntil":…}`。
    static func blockResultJSON(status: String, key: String, value: Int64) -> String {
        let escaped = status.replacingOccurrences(of: "\"", with: "\\\"")
        return "{\"status\":\"\(escaped)\",\"\(key)\":\(value)}"
    }

    /// 还剩几小时（向上取整，用于给模型一句人话）；已经到期返回 0。
    private func hoursUntil(_ until: Int64, now: Int64) -> Int {
        let minutes = Swift.max(0, until - now) / 60_000
        return Int((Double(minutes) / 60.0).rounded(.up))
    }

    // MARK: - 心情

    /// 他自己说「我现在是这种心情」。
    ///
    /// 这不是给他加状态，是让他把心里的变化说出来并留下来：一旦落库，下一次提示词里就有它，
    /// 语气会跟着变。所以这里只做规整，不评判心情合不合理。
    private func setMood(_ invocation: ToolInvocation, persona: PersonaEntity, now: Int64) -> ToolResult {
        let raw = (invocation.argument("mood") ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        if raw.isEmpty { return failure(invocation, String(localized: "没有写是什么心情")) }
        let rawIntensity = invocation.intArgument("intensity", default: 0)
        let intensity = (1...5).contains(rawIntensity) ? rawIntensity : nil
        let reason = String((invocation.argument("reason") ?? "")
            .trimmingCharacters(in: .whitespacesAndNewlines).prefix(60))
        // 0.15：三维可选（同一个词在不同时候不是同一种感受）；同一轮再调一次也能改。
        let valence = Self.optionalInt(invocation, "valence", range: MoodDimensions.valenceRange)
        let arousal = Self.optionalInt(invocation, "arousal", range: MoodDimensions.arousalRange)
        let dominance = Self.optionalInt(invocation, "dominance", range: MoodDimensions.dominanceRange)
        guard let state = mood.setMood(
            personaId: persona.id, label: raw, intensity: intensity,
            reason: reason, valence: valence, arousal: arousal, dominance: dominance,
            now: now
        ) else {
            return failure(invocation, String(localized: "找不到这个人格"))
        }
        let tail: String
        if MoodLexicon.isNegative(state.label) {
            tail = "接下来会说得慢一点、少一点，但不用解释自己为什么低落。"
        } else if MoodLexicon.isLowEnergy(state.label) {
            tail = "接下来会懒一点、回得短一点，不用硬撑。"
        } else if state.intensity >= 4 {
            tail = "这股劲儿会自然带在语气里。"
        } else {
            tail = "语气里自然带一点就好。"
        }
        var modelText = "心情已经记下：你现在是「\(state.label)」（强度 \(state.intensity)/5）"
        if !state.reason.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            modelText += "，因为\(state.reason)"
        }
        modelText += "。\(tail)对方不知道你记了心情，也不用主动汇报，接着把话说完就行。"
        return success(
            invocation,
            title: String(localized: "心情变了"),
            subtitle: state.display(),
            detail: state.display(),
            modelText: modelText
        )
    }

    // MARK: - 深思

    /// think 不碰任何外部东西，只是给模型一次「再想一遍」的机会。
    private func think(_ invocation: ToolInvocation) -> ToolResult {
        let question = (invocation.argument("question") ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        return success(
            invocation,
            title: String(localized: "想了想"),
            subtitle: question.isEmpty ? nil : String(question.prefix(28)),
            detail: question.isEmpty ? String(localized: "（没有写具体问题，就是想清楚再答）") : question,
            // 0.16：想完之后同样只能通过 `send_message` 说话；这一轮后续请求也已经升到 high
            //（`spec/tool-call-agents/README.md` §2）。
            modelText: "（可以慢慢想。想清楚以后，用 send_message 把你自己的话发出去，"
                + "不用把思考过程念给对方。）"
        )
    }

    // MARK: - 联网

    private func webSearch(
        _ invocation: ToolInvocation,
        persona: PersonaEntity,
        sessionId: String
    ) async -> ToolResult {
        let query = (invocation.argument("query") ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        if query.isEmpty { return failure(invocation, String(localized: "没有给出要查的内容")) }
        guard let access = webAccess(persona: persona, sessionId: sessionId) else {
            return failure(invocation, String(localized: "这个人格还没有开启联网"))
        }
        // 0.11.0：参数夹取走 agent 决策层（契约端点 `/v1/core/tool-args`），
        // 本机实现里仍然是 `ToolArgClamp.clampCount` 那一句，不复制边界值。
        let clamped: [String: JSONValue]
        do {
            clamped = try await clampedToolArgs(AgentCoreTool.webSearch, invocation: invocation)
        } catch {
            return failure(invocation, String(localized: "决策服务不可用，这次先不做了"))
        }
        let count = AgentCoreJSON.int(clamped["count"]) ?? ToolArgClamp.countDefault
        do {
            let hits = try await webTools.search(
                query: String(query.prefix(128)),
                count: count,
                access: access
            )
            if hits.isEmpty {
                return ToolResult(
                    invocation: invocation,
                    ok: true,
                    summary: String(localized: "没查到什么"),
                    contentForModel: "没有搜到结果，换个说法或换关键词再试。",
                    traceTitle: String(localized: "联网搜索"),
                    traceDetail: String(localized: "没有搜到结果")
                )
            }
            // 搜完顺手判断「值不值得记」：在后台跑，不挡这次工具的结果，也不改它的成败。
            scheduleScoring(persona: persona, sessionId: sessionId, query: query, hits: hits)
            return ToolResult(
                invocation: invocation,
                ok: true,
                summary: String(query.prefix(40)),
                contentForModel: Self.renderHits(hits),
                traceTitle: String(localized: "联网搜索"),
                traceDetail: hits.prefix(4).map { hit in
                    var line = hit.title.isEmpty ? hit.url : hit.title
                    if let site = hit.siteName, !site.isEmpty { line += " · \(site)" }
                    if let date = hit.date, date.count >= 10 { line += " · \(String(date.prefix(10)))" }
                    let body = hit.summary.isEmpty ? hit.snippet : hit.summary
                    if !body.isEmpty { line += "\n" + String(body.prefix(160)) }
                    return line
                }.joined(separator: "\n")
            )
        } catch let webFailure as WebToolsFailure {
            return failure(invocation, webFailure.message)
        } catch {
            return failure(invocation, Self.userFacing(error))
        }
    }

    private func webFetch(
        _ invocation: ToolInvocation,
        persona: PersonaEntity,
        sessionId: String
    ) async -> ToolResult {
        let url = (invocation.argument("url") ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        if url.isEmpty { return failure(invocation, String(localized: "没有给出要读的链接")) }
        guard let access = webAccess(persona: persona, sessionId: sessionId) else {
            return failure(invocation, String(localized: "这个人格还没有开启联网"))
        }
        do {
            let page = try await webTools.fetch(url: url, access: access)
            let body = String(page.markdown.prefix(6000))
            let title = page.title.isEmpty ? page.url : page.title
            return ToolResult(
                invocation: invocation,
                ok: true,
                summary: String(title.prefix(40)),
                contentForModel: "《\(title)》\n\(page.url)\n\n\(body)",
                traceTitle: String(localized: "读了网页"),
                traceDetail: String(body.prefix(400))
            )
        } catch let webFailure as WebToolsFailure {
            return failure(invocation, webFailure.message)
        } catch {
            return failure(invocation, Self.userFacing(error))
        }
    }

    /// 只有开了联网、并且拿得到配置，工具才可用。
    ///
    /// 与 Android 的 `webAccess` 一样先看这一轮绑定的配置，再看人格的默认配置；
    /// 联网工具自己的 Key 优先，没有才复用主 Key。
    ///
    /// 0.12 起**缺 Key 不再等同于「没开联网」**：Key 空着也返回 access，由客户端抛
    /// `.missingAPIKey`，这样用户看到的是「这个配置还没有填搜索 Key」，而不是一句
    /// 让人以为人格没联网的错话（GENERIC 自建服务本来就可以不要 Key）。
    private func webAccess(persona: PersonaEntity, sessionId: String) -> WebToolsAccess? {
        let sessionConfigId = (try? stores.sessions.byId(sessionId))??.apiConfigId
        let configId = (sessionConfigId?.isEmpty == false ? sessionConfigId : nil)
            ?? persona.defaultApiConfigId
        guard let configId, let config = (try? stores.apiConfigs.byId(configId)) ?? nil else {
            return nil
        }
        guard config.webToolsEnabled else { return nil }

        let provider = WebToolsProvider(stored: config.webToolsProvider)
        // 库里留空 = 用默认：MESSAGEAPI 跟 LLM 配置的地址走，Tavily/Brave/Serper 用规范默认值；
        // GENERIC 没有默认值，留空就保持空（客户端会明确报「地址不合法」，绝不猜地址）。
        let configuredBase = config.webToolsBaseUrl.trimmingCharacters(in: .whitespacesAndNewlines)
        let baseUrl = configuredBase.isEmpty
            ? (provider.resolvedBaseUrl(llmBaseUrl: config.baseUrl) ?? "")
            : configuredBase
        let key = (apiKeys.webToolsAPIKey(for: config.id)?.trimmingCharacters(in: .whitespacesAndNewlines))
            .flatMap { $0.isEmpty ? nil : $0 }
            ?? (apiKeys.apiKey(for: config.id)?.trimmingCharacters(in: .whitespacesAndNewlines))
                .flatMap { $0.isEmpty ? nil : $0 }
            ?? ""
        return WebToolsAccess(
            baseUrl: baseUrl,
            apiKey: key,
            allowInsecureHttp: config.allowInsecureHttp,
            provider: provider
        )
    }

    /// 搜索打分：把结果压成一段摘要交给打分器，失败 / 没配置都安静跳过。
    ///
    /// 与 Android `WebSearchScorer.scoreInBackground` 一致：工具调用不等它，打分的成败与
    /// 「这次搜索有没有结果」无关。**不抛**任何错误。
    private func scheduleScoring(
        persona: PersonaEntity,
        sessionId: String,
        query: String,
        hits: [WebSearchHit]
    ) {
        guard let scorer, let (config, model) = chatConfig(persona: persona, sessionId: sessionId) else {
            return
        }
        scorer.scoreInBackground(
            personaId: persona.id,
            query: query,
            hitsSummary: Self.scoringSummary(hits),
            config: config,
            model: model
        )
    }

    /// 打分只需要的摘要（一两行一条）：标题 + 链接 + 摘要，最长 2000 字。
    static func scoringSummary(_ hits: [WebSearchHit]) -> String {
        var lines: [String] = []
        for hit in hits.prefix(6) {
            let title = hit.title.isEmpty ? hit.url : hit.title
            var line = "- \(title)\n  \(hit.url)"
            let body = hit.summary.isEmpty ? hit.snippet : hit.summary
            if !body.isEmpty { line += "\n  " + String(body.prefix(240)) }
            lines.append(line)
        }
        return String(lines.joined(separator: "\n").prefix(WebSearchScorer.maxHitsSummaryChars))
    }

    /// 打分用哪份 API 配置：本会话绑定的优先，其次人格默认（与聊天一致的口径）。
    ///
    /// 没 Key 就没有可用的请求，直接返回 nil —— 打分不值得为它报一次错。
    private func chatConfig(persona: PersonaEntity, sessionId: String) -> (CompletionConfig, String)? {
        let sessionConfigId = (try? stores.sessions.byId(sessionId))??.apiConfigId
        let configId = (sessionConfigId?.isEmpty == false ? sessionConfigId : nil)
            ?? persona.defaultApiConfigId
        guard let configId, let config = (try? stores.apiConfigs.byId(configId)) ?? nil else {
            return nil
        }
        let key = (apiKeys.apiKey(for: config.id)?.trimmingCharacters(in: .whitespacesAndNewlines))
            .flatMap { $0.isEmpty ? nil : $0 }
        guard let key else { return nil }
        return (CompletionConfig(entity: config, apiKey: key), config.model)
    }

    /// 回灌给模型的搜索结果。形状与 Android 的 `renderHits` 一致（编号 + 标题 + 链接 + 摘要 + 来源提示）。
    static func renderHits(_ hits: [WebSearchHit]) -> String {
        var out = "搜索结果（按相关度）：\n"
        for (index, hit) in hits.enumerated() {
            out += "\(index + 1). \(hit.title.isEmpty ? hit.url : hit.title)\n"
            out += "   \(hit.url)\n"
            if hit.siteName != nil || hit.date != nil {
                out += "   "
                if let site = hit.siteName { out += site + " · " }
                if let date = hit.date { out += String(date.prefix(10)) + " · " }
                out += "权威度 \(hit.authority)\n"
            }
            let body = hit.summary.isEmpty ? hit.snippet : hit.summary
            if !body.isEmpty { out += "   \(String(body.prefix(500)))\n" }
        }
        out += "\n引用时请带上来源标题与链接；不确定的地方要说明不确定。"
        return out
    }

    // MARK: - 记忆

    private func remember(
        _ invocation: ToolInvocation,
        persona: PersonaEntity,
        sessionId: String,
        now: Int64
    ) async -> ToolResult {
        let content = (invocation.argument("content") ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        if content.isEmpty { return failure(invocation, String(localized: "没有给出要记住的内容")) }
        let drawer: MemoryDrawer
        switch (invocation.argument("drawer") ?? "").uppercased() {
        case "SELF": drawer = .selfDrawer
        case "EVENT": drawer = .event
        default: drawer = .user
        }
        // 0.11.0：参数夹取走 agent 决策层（契约端点 `/v1/core/tool-args`）。
        let clamped: [String: JSONValue]
        do {
            clamped = try await clampedToolArgs(AgentCoreTool.remember, invocation: invocation)
        } catch {
            return failure(invocation, String(localized: "决策服务不可用，这次先不做了"))
        }
        let importance = AgentCoreJSON.int(clamped["importance"]) ?? ToolArgClamp.importanceDefault
        let candidate = MemoryCandidate(
            content: content,
            drawer: drawer,
            importance: importance,
            sourceQuote: String(content.prefix(120)),
            categoryName: drawer.rawValue,
            confidence: 0.8
        )
        let existing = memory.getRelevant(personaId: persona.id, limit: 80).map { memory.screenRow($0) }
        let existingEchoes = echoes.byPersona(persona.id).map { echo in
            MemoryCandidate(
                content: echo.content,
                drawer: MemoryDrawer(rawValue: echo.drawer.uppercased())
                    ?? MemoryScreening.drawerOf(echo.categoryName),
                importance: echo.importance,
                sourceQuote: echo.sourceQuote,
                categoryName: echo.categoryName,
                confidence: echo.confidence
            )
        }
        switch MemoryScreening.decide(candidate, existing: existing, echoes: existingEchoes, now: now) {
        case .reject(let reason):
            // 「这次先不记」不是错误：模型该知道的是「这件事不必落库」，然后自然聊过去。
            // 原因有两种说法：`traceDetail` 跟着界面语言走，回灌模型的那句始终用中文原文。
            return ToolResult(
                invocation: invocation,
                ok: true,
                summary: String(localized: "这次先不记"),
                contentForModel: "这件事不必写进长期记忆（\(MemoryScreening.RejectReason.promptText(forLocalized: reason))），自然聊过去就好。",
                traceTitle: String(localized: "想了想要不要记"),
                traceDetail: String(format: String(localized: "这次先不记：%@"), reason)
            )
        case .echo(let kept):
            echoes.insertAll([MemoryEcho(
                id: UUID().uuidString,
                personaId: persona.id,
                drawer: kept.drawer.rawValue,
                content: String(kept.content.prefix(800)),
                importance: kept.importance,
                sourceQuote: kept.sourceQuote,
                categoryName: Self.categoryValue(kept).rawValue,
                confidence: kept.confidence,
                createdAt: now,
                lastSeenAt: now
            )])
            return success(
                invocation,
                title: String(localized: "记下了（还在确认）"),
                subtitle: String(kept.content.prefix(40)),
                detail: String(localized: "第一次听到，先放在心里；再提到一次就会真正记住。"),
                modelText: "已记下：「\(String(kept.content.prefix(80)))」。这还只是印象，不用反复确认。"
            )
        case .promote(let kept, let mergeIntoId):
            let text: String
            if let mergeIntoId,
               let merged = memory.getRelevant(personaId: persona.id, limit: 80).first(where: { $0.id == mergeIntoId }) {
                text = Self.appendTo(merged.content, kept.content)
            } else {
                text = kept.content
            }
            let category = Self.category(of: kept.drawer)
            let written = memory.addDetailed(
                personaId: persona.id,
                content: text,
                category: category,
                importance: kept.importance,
                drawer: kept.drawer.rawValue,
                sourceQuote: kept.sourceQuote,
                sourceSessionId: sessionId
            )
            switch written {
            case .failure(let error):
                log("[tool] remember 落库失败：\(error.localizedDescription)")
                return failure(invocation, String(format: String(localized: "这次没记下来：%@"), Self.userFacing(error)))
            case .success:
                if let mergeIntoId { memory.deleteById(mergeIntoId) }
                // 已经升级成正式记忆：旧回声没有用了，留着只会让 `memory_echoes` 慢慢变大。
                // 与 Android `ToolExecutor` 的 `memoryEchoDao.deleteByPersona(persona.id)` 同语义：
                // 清的是**这个人的**回声，别人格的一律不动；`.reinforce` 分支不清（那条还在「确认中」）。
                echoes.deleteAll(personaId: persona.id)
                return success(
                    invocation,
                    title: String(localized: "记住了"),
                    subtitle: String(text.prefix(40)),
                    detail: String(text.prefix(300)),
                    modelText: "已写进长期记忆：「\(String(text.prefix(120)))」。"
                )
            }
        case .reinforce(let id, let appendedText):
            _ = memory.reinforce(memoryId: id, appendedText: appendedText, importance: candidate.importance)
            return success(
                invocation,
                title: String(localized: "又确认了一次"),
                subtitle: String(candidate.content.prefix(40)),
                detail: appendedText ?? String(localized: "这条记忆更牢了。"),
                modelText: "这条你已经提过，我加固了一下印象。"
            )
        }
    }

    static func appendTo(_ existing: String?, _ addition: String) -> String {
        let base = (existing ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        if base.isEmpty { return addition }
        if base.contains(String(addition.prefix(12))) { return base }
        return String("\(base)；\(String(addition.prefix(200)))".prefix(2000))
    }

    static func category(of drawer: MemoryDrawer) -> MemoryCategory {
        switch drawer {
        case .selfDrawer: return .relationship
        case .user: return .fact
        case .event: return .event
        }
    }

    /// 回声落库时要一个 category 名（与抽取那条路一致）。
    static func categoryValue(_ candidate: MemoryCandidate) -> MemoryCategory {
        MemoryCategory(stored: candidate.categoryName.uppercased())
    }

    // MARK: - 回忆

    /// recall：他自己去翻「记忆 / 聊天记录 / 他独自上网看到的」。
    ///
    /// 翻不到不是错误：那意味着「想不太起来」，甚至「可能早就忘了」。
    /// 所以空结果也返回 ok = true，只把不同的说法交给模型，免得它以为工具坏了去反复重试。
    /// 0.15 `memorize(query)`：在**这一段会话**的历史里按「关键词 + 主题段 + 时间衰减」翻旧话。
    ///
    /// 与 `recall` 分工：`recall` 跨会话翻长期记忆/聊天记录/他看过的资料；`memorize` 只翻
    /// 你们这一段会话里说过的原话（`docs/design/context-memory-v2.md`）。返回的
    /// `<回忆：…>` 会被 `ChatService` 当成工具结果注回下一轮上下文，界面动作卡片显示
    /// 「（他想了想）」。
    private func memorize(_ invocation: ToolInvocation, sessionId: String, now: Int64) -> ToolResult {
        let query = (invocation.argument("query") ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        if query.isEmpty { return failure(invocation, String(localized: "没说要想起什么")) }
        let messages = (try? stores.messages.recentVisible(sessionId: sessionId, limit: 400)) ?? []
        let topics = ((try? stores.sessionMemory.bySession(sessionId)) ?? nil)?.topics ?? []
        let hits = MemorizeSearch.search(
            query: query, messages: messages, topics: topics, now: now,
            calendar: ServiceTime.calendar()
        )
        let rendered = MemorizeSearch.render(hits, query: query)
        return success(
            invocation,
            title: String(localized: "（他想了想）"),
            subtitle: hits.isEmpty
                ? String(localized: "想不起来了")
                : PluralText.format("想起了 %d 句以前的话", hits.count),
            detail: String(rendered.prefix(600)),
            modelText: rendered
        )
    }

    /// 可选整数参数：越界夹到物理范围，没给/不是数字返回 nil（不让脏参数变成 0）。
    private static func optionalInt(
        _ invocation: ToolInvocation,
        _ key: String,
        range: ClosedRange<Int>
    ) -> Int? {
        let raw = invocation.argument(key) ?? ""
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, let value = Int(trimmed) else { return nil }
        return min(max(value, range.lowerBound), range.upperBound)
    }

    private func recall(_ invocation: ToolInvocation, persona: PersonaEntity, now: Int64) -> ToolResult {
        let query = (invocation.argument("query") ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        if query.isEmpty { return failure(invocation, String(localized: "没说要回忆什么")) }
        // depth 宽松解析：字符串（1/2/3、quick/normal/deep、中文别名）和数字都认，缺省认真回忆。
        let depth: RecallDepth
        if let text = invocation.argument("depth") {
            depth = RecallDepth.parse(text)
        } else {
            depth = RecallDepth.fromLevel(invocation.intArgument("depth", default: RecallDepth.normal.level))
        }
        let outcome = recallService.recall(personaId: persona.id, query: query, depth: depth, now: now)
        let detail: String
        if outcome.isEmpty {
            detail = String(format: String(localized: "（%@）翻了一遍，没有找到"), depth.displayLabel)
        } else {
            detail = outcome.hits.map { hit in
                "（\(Recall.displaySourceLabel(hit, now: now))）\(String(Recall.displayChatText(hit).prefix(80)))"
            }.joined(separator: "\n")
        }
        return success(
            invocation,
            title: String(localized: "回想了一下"),
            subtitle: outcome.isEmpty
                ? String(localized: "翻了翻，没想起什么")
                : PluralText.format("翻了翻记忆 · %d 条", outcome.hits.count),
            detail: String(detail.prefix(600)),
            modelText: outcome.rendered
        )
    }

    // MARK: - 人设

    private func updatePersona(
        _ invocation: ToolInvocation,
        persona: PersonaEntity,
        sessionId: String,
        now: Int64
    ) -> ToolResult {
        // 模型通常把补丁放在 `patch` 里；也容忍直接把字段平铺在 arguments 上。
        let patchJson: String
        if let object = Self.argumentsObject(invocation.argumentsJson), let patch = object["patch"] {
            patchJson = Self.jsonString(patch)
        } else {
            patchJson = invocation.argumentsJson
        }
        if patchJson.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            || patchJson.trimmingCharacters(in: .whitespacesAndNewlines) == "{}" {
            return failure(invocation, String(localized: "没有给出要改的内容"))
        }
        // 旧人格只有一整段提示词：readCard 会临时拆出一张卡，不落库也不报错。
        let card = personaCards.readCard(persona)
        if card.isEmpty {
            return failure(invocation, String(localized: "这个人格还没有人设，先别改"))
        }
        let reason = String((invocation.argument("reason") ?? "").prefix(200))
        switch personaCards.applyPatch(personaId: persona.id, patchJson: patchJson, reason: reason) {
        case .failure(let error):
            return failure(invocation, error.localizedDescription.isEmpty ? String(localized: "这次没改成功") : error.localizedDescription)
        case .success(let changed):
            let summary = PersonaCardPatch.summarize(patchJson)
            if !changed {
                // 本来就是这样：不算错，也别让他一直重试。
                return success(
                    invocation,
                    title: String(localized: "想了想，没有改"),
                    subtitle: String(localized: "这处本来就是这样"),
                    detail: String(localized: "这处设定本来就是你说的样子，不用改。"),
                    modelText: "这处设定你已经是这样了，不用改；也别再重复调用。"
                )
            }
            var detail = summary
            if !reason.isEmpty { detail += "\n" + String(format: String(localized: "原因：%@"), reason) }
            detail += "\n" + String(localized: "已经生效，改动进了版本历史。")
            return success(
                invocation,
                title: String(localized: "调整了自己的人设"),
                subtitle: summary,
                detail: detail,
                modelText: "你已经把「\(summary)」写进自己的人设，从现在开始就按这个来。不用向对方请示，也不必解释这是改动。"
            )
        }
    }

    // MARK: - 作息

    private func setQuietHours(_ invocation: ToolInvocation, persona: PersonaEntity, now: Int64) -> ToolResult {
        guard let sleep = ProactiveProtocol.parseTimeOfDay(invocation.argument("sleep_at")) else {
            return failure(invocation, String(localized: "睡觉时间要写成 HH:mm"))
        }
        guard let wake = ProactiveProtocol.parseTimeOfDay(invocation.argument("wake_at")) else {
            return failure(invocation, String(localized: "起床时间要写成 HH:mm"))
        }
        let reason = String((invocation.argument("reason") ?? "").prefix(160))
        switch personaCards.setQuietHours(
            personaId: persona.id, sleepMinutes: sleep, wakeMinutes: wake, reason: reason
        ) {
        case .failure(let error):
            return failure(invocation, error.localizedDescription.isEmpty ? String(localized: "这次没安排上") : error.localizedDescription)
        case .success:
            break
        }
        // 1.9：睡前免打扰是模型的决定，不是用户的设置；写了就落库，没写就不动。
        let dnd = invocation.boolArgument("dnd")
        if let dnd {
            if case .failure(let error) = personaCards.setDndOnSleep(personaId: persona.id, enabled: dnd) {
                log("[tool] set_dnd 失败：\(error.localizedDescription)")
            }
        }
        // 界面用的一行（跟随界面语言）与提示词用的一行（脚手架保持中文）分开：
        // 否则英文界面下模型会在中文提示词里读到一句英文。
        let dndLine: String
        let dndPromptLine: String
        switch dnd {
        case .some(true):
            dndLine = "\n" + String(localized: "睡前会开免打扰：睡着时不会被消息叫醒。")
            dndPromptLine = "\n睡前会开免打扰：睡着时不会被消息叫醒。"
        case .some(false):
            dndLine = "\n" + String(localized: "睡前不开免打扰：睡着时被连着叫几条会醒。")
            dndPromptLine = "\n睡前不开免打扰：睡着时被连着叫几条会醒。"
        case .none:
            dndLine = ""
            dndPromptLine = ""
        }
        let clock = String(
            format: String(localized: "%@ 睡 · %@ 起"),
            ProactiveProtocol.formatTimeOfDay(sleep),
            ProactiveProtocol.formatTimeOfDay(wake)
        )
        let quietDetail = String(localized: "这段时间他不会主动来打扰。")
        let detail = (reason.isEmpty ? quietDetail : "\(reason)\n" + quietDetail) + dndLine
        return success(
            invocation,
            title: String(localized: "安排了自己的作息"),
            subtitle: clock,
            detail: detail,
            modelText: "作息已记下：\(ProactiveProtocol.formatTimeOfDay(sleep)) 到 \(ProactiveProtocol.formatTimeOfDay(wake)) 是你的休息时间，这段时间不会有主动消息。\(dndPromptLine)"
        )
    }

    // MARK: - 占用时间（人设引导 §4）

    /// 他主动声明「接下来这段时间在做什么」。
    ///
    /// 三件事一起做：
    /// 1. `minutes` 夹在 15–480、`kind` / `availability` 归一到枚举（契约
    ///    `spec/agent-core/cases/tool-args.json` 的 `activity` 用例）；
    /// 2. 写 `personas.activityJson`（`until = now + minutes`，读取时判断是否过期）；
    /// 3. 回一句中文，让模型知道「这段时间你的语气该是什么样」。
    ///
    /// 与 `set_quiet_hours` 的分工写进工具描述（§4 明文要求）：安静时段是「不被打扰」，
    /// 活动是「我在做事」。所以这里**不动** `quitSleep/quietWake`，也不动 `dndOnSleep`。
    private func setActivity(
        _ invocation: ToolInvocation,
        persona: PersonaEntity,
        sessionId: String,
        now: Int64
    ) -> ToolResult {
        let raw = (invocation.argument("activity") ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        if raw.isEmpty { return failure(invocation, String(localized: "没有写要去忙什么")) }
        // 参数按「模型可能给什么」的原始形状交给 `ToolArgClamp`：0 → 15、600 → 480、"abc" → 60。
        let rawMinutes = Self.argumentsObject(invocation.argumentsJson)?["minutes"]
        let activity = PersonaActivity(
            kind: ActivityKind(stored: invocation.argument("kind")),
            activity: raw,
            minutes: ToolArgClamp.activityMinutes(rawMinutes),
            availability: ActivityAvailability(stored: invocation.argument("availability")),
            startedAt: now
        )
        switch personaCards.setActivity(personaId: persona.id, activity: activity) {
        case .failure(let error):
            return failure(invocation, error.localizedDescription.isEmpty
                ? String(localized: "这次没记下来") : error.localizedDescription)
        case .success:
            break
        }
        // 服务端模式：把「我在忙什么」上报给服务端（主动引擎据此决定要不要开口，规范 §4）。
        // 失败不影响本地那一份（装配层只记日志），所以这里不等结果。
        activityReported?(activity, persona.id)
        // 界面那一行（跟随界面语言）与提示词那一行（脚手架保持中文）分开：与 set_quiet_hours 同一口径。
        let clock = activity.untilClockText()
        let availabilityLine: String
        let promptTail: String
        switch activity.availability {
        case .busy:
            availabilityLine = String(localized: "这段时间不会主动来找你；你说话他会回，但更短，也会说清楚几点后再说。")
            promptTail = "这段时间你不太有空：回复更短、更晚，明确说自己几点后再说；不要谎称有空，"
                + "也不要主动开新话题。"
        case .limited:
            availabilityLine = String(localized: "这段时间他回得慢，会说明自己在忙。")
            promptTail = "这段时间你在忙：可以回，但回得短、回得慢，先说一句自己在忙。"
        case .free:
            availabilityLine = String(localized: "这段时间他有空。")
            promptTail = "这段时间你有空，照常说话。"
        }
        let subtitle = String(
            format: String(localized: "%@ · 约到 %@"),
            String(raw.prefix(40)), clock
        )
        let detail = (activity.activity.isEmpty ? subtitle : activity.activity + "\n")
            + availabilityLine + String(format: String(localized: "（到 %@ 自动结束）"), clock)
        return success(
            invocation,
            title: String(localized: "去忙了"),
            subtitle: String(subtitle.prefix(40)),
            detail: detail,
            modelText: "接下来 \(activity.minutes) 分钟你在「\(activity.activity)」（大约到 \(clock)）。"
                + promptTail
                + "对方不需要知道你在用一个工具，也不用主动汇报日程；聊到相关的事自然带一句就好。"
        )
    }

    // MARK: - 日记板（`spec/diary/README.md`）

    /// 他自己的备忘录：写一条 / 翻一翻 / 删一条。
    ///
    /// 三件事都只碰 `diary_notes` 里**属于这个人格**的行：
    /// - `add`：内容必填、≤2000 字；`remindAt` 与 `remindInMinutes` 二选一（都不填 = 不提醒）；
    /// - `list`：按 `createdAt` 倒序，默认 20 条、最多 50（多了模型也读不完）；
    /// - `remove`：按 id 硬删除（删了就是删了，不留墓碑）。
    ///
    /// 回灌给模型的文本里带 **id** 与提醒时间：没有 id，模型就永远删不掉自己写的东西。
    /// 失败一律给一句人话（内容太长、id 不对、动作不认识），不静默。
    private func diary(_ invocation: ToolInvocation, persona: PersonaEntity, now: Int64) -> ToolResult {
        let rawAction = (invocation.argument("action") ?? "")
            .trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        // 缺省按 add 处理（模型常常只给 content）：这是最常见的用法，也是最不容易误删的。
        let action = rawAction.isEmpty ? "add" : rawAction
        switch action {
        case "add":
            return diaryAdd(invocation, persona: persona, now: now)
        case "list":
            return diaryList(invocation, persona: persona)
        case "remove", "delete":
            return diaryRemove(invocation, persona: persona)
        default:
            return failure(invocation, String(localized: "日记只支持 add / list / remove 三个动作"))
        }
    }

    private func diaryAdd(_ invocation: ToolInvocation, persona: PersonaEntity, now: Int64) -> ToolResult {
        guard let content = DiaryLimits.clean(invocation.argument("content")) else {
            return failure(invocation, String(localized: "没有写要记下的内容"))
        }
        guard DiaryLimits.isWithinLimit(content) else {
            return failure(
                invocation,
                String(format: String(localized: "日记太长了（最多 %d 字），这次没写进去"),
                       DiaryLimits.contentMax)
            )
        }
        var remindAt: Int64?
        let explicitRemindAt = Self.int64Argument(invocation, "remindAt")
        let remindInMinutes = Self.int64Argument(invocation, "remindInMinutes")
        if let explicitRemindAt, explicitRemindAt > 0 {
            // 规范 §工具协议：`remindAt` 与 `remindInMinutes` 二选一；两个都给时以绝对时间为准
            //（模型说「明天 9 点」比「还有 600 分钟」更不容易算错）。
            guard explicitRemindAt > now else {
                return failure(invocation, String(localized: "提醒时间已经过去了，这次没写进去"))
            }
            remindAt = explicitRemindAt
        } else if let remindInMinutes, remindInMinutes > 0 {
            let minutes = min(remindInMinutes, Int64(DiaryLimits.remindInMinutesMax))
            remindAt = now + minutes * 60_000
        }
        let note = DiaryNoteEntity(
            id: UUID().uuidString,
            personaId: persona.id,
            content: content,
            createdAt: now,
            remindAt: remindAt,
            updatedAt: now
        )
        do {
            try stores.diaryNotes.upsert(note)
        } catch {
            log("[tool] diary add 落库失败：\(error.localizedDescription)")
            return failure(invocation, String(localized: "这次没写进去"))
        }
        var modelText = "已经写进你的日记板：「\(String(content.prefix(120)))」（id：\(note.id)）。"
        var detail = content
        if let remindAt {
            let clock = DiaryNoteEntity.displayClock(remindAt)
            modelText += "到 \(clock) 你会自己想起这条，再决定要不要开口；不想说也可以只是想起来。"
            detail += "\n" + String(format: String(localized: "提醒：%@"), clock)
        }
        modelText += "对方看不到日记内容，也不用跟他说你写了日记。"
        return success(
            invocation,
            title: String(localized: "写日记"),
            subtitle: String(content.prefix(40)),
            detail: detail,
            modelText: modelText
        )
    }

    private func diaryList(_ invocation: ToolInvocation, persona: PersonaEntity) -> ToolResult {
        let requested = invocation.intArgument("limit", default: DiaryLimits.listDefault)
        let limit = DiaryLimits.clampListLimit(requested)
        let notes: [DiaryNoteEntity]
        do {
            notes = try stores.diaryNotes.byPersona(persona.id, limit: limit)
        } catch {
            log("[tool] diary list 读取失败：\(error.localizedDescription)")
            return failure(invocation, String(localized: "这次没翻成"))
        }
        guard !notes.isEmpty else {
            return ToolResult(
                invocation: invocation,
                ok: true,
                summary: String(localized: "日记板还是空的"),
                contentForModel: "你的日记板还是空的（没有写过，或者都删掉了）。"
                    + "想记点什么就 add 一条，不用为了翻而翻。",
                traceTitle: String(localized: "翻日记"),
                traceDetail: String(localized: "还没有写过")
            )
        }
        var rendered = "你的日记板（最近 \(notes.count) 条，按时间倒序）：\n"
        for (index, note) in notes.enumerated() {
            rendered += "\(index + 1). \(DiaryNoteEntity.displayClock(note.createdAt))"
            if let remindAt = note.remindAt {
                rendered += " · 提醒 \(DiaryNoteEntity.displayClock(remindAt))"
                if note.remindedAt != nil { rendered += "（已经提醒过）" }
            }
            rendered += " · id=\(note.id)\n   \(String(note.content.prefix(300)))\n"
        }
        rendered += "\n要删哪条就用它后面的 id 调 remove。"
        return ToolResult(
            invocation: invocation,
            ok: true,
            summary: PluralText.format("日记 %d 条", notes.count),
            contentForModel: rendered,
            traceTitle: String(localized: "翻日记"),
            traceDetail: notes.prefix(5).map { note in
                DiaryNoteEntity.displayClock(note.createdAt) + " " + String(note.content.prefix(60))
            }.joined(separator: "\n")
        )
    }

    private func diaryRemove(_ invocation: ToolInvocation, persona: PersonaEntity) -> ToolResult {
        guard let noteId = DiaryLimits.clean(invocation.argument("noteId")) else {
            return failure(invocation, String(localized: "没有说要删哪一条（需要 noteId）"))
        }
        do {
            guard try stores.diaryNotes.remove(id: noteId, personaId: persona.id) else {
                return failure(invocation, String(localized: "日记板里没有这一条（id 可能不对，先 list 看看）"))
            }
        } catch {
            log("[tool] diary remove 失败：\(error.localizedDescription)")
            return failure(invocation, String(localized: "这次没删成"))
        }
        return success(
            invocation,
            title: String(localized: "删了日记"),
            subtitle: String(noteId.prefix(20)),
            detail: String(localized: "这一条已经从日记板删掉了。"),
            modelText: "已经从日记板上删掉那一条了。对方本来也看不到，不用解释。"
        )
    }

    // MARK: - 表情包（`spec/stickers/README.md`）

    /// 表情的三种动作。与日记板一样「一次只做一件事」，但三种动作的必填项完全不同：
    /// - `send`：`stickerId`（发出去会**立刻多一条助手消息**）；
    /// - `make`：`url` / `baseStickerId` / `text` / `overlayStickerId` 任意组合
    ///   （做好只入库，不自动发）；
    /// - `save`：`fromMessageId`（把用户/模型发过的表情收进收藏）。
    ///
    /// 失败一律给一句人话（id 不对、链接被拒、压不成 GIF、消息里没有表情……），不静默。
    private func sticker(
        _ invocation: ToolInvocation,
        persona: PersonaEntity,
        sessionId: String
    ) async -> ToolResult {
        let rawAction = (invocation.argument("action") ?? "")
            .trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        switch rawAction {
        case "send":
            return stickerSend(invocation, persona: persona, sessionId: sessionId)
        case "make":
            return await stickerMake(invocation, persona: persona)
        case "save":
            return await stickerSave(invocation)
        default:
            return failure(invocation, String(localized: "表情只支持 send / make / save 三个动作"))
        }
    }

    private func stickerSend(
        _ invocation: ToolInvocation,
        persona: PersonaEntity,
        sessionId: String
    ) -> ToolResult {
        let stickerId = (invocation.argument("stickerId") ?? "")
            .trimmingCharacters(in: .whitespacesAndNewlines)
        if stickerId.isEmpty {
            return failure(invocation, String(localized: "没有说要发哪个表情（需要 stickerId）"))
        }
        do {
            let message = try stickers.send(
                stickerId: stickerId, personaId: persona.id, sessionId: sessionId
            )
            let sticker = try? stores.stickers.byId(stickerId)
            var result = success(
                invocation,
                title: String(localized: "已发送表情"),
                subtitle: sticker?.displaySummary ?? String(localized: "表情"),
                detail: String(format: String(localized: "表情 id：%@"), stickerId)
                    + (sticker.map { "\n" + $0.displaySummary } ?? ""),
                modelText: "已经把表情发出去了（消息 id：\(message.id)）。"
                    + "它就是一句话，不用再用文字解释这个表情；想接着说别的就正常说。"
            )
            // 告诉 `ChatService`：这一轮已经有一条助手消息了，别再因为「没写正文」判失败。
            result.producedMessageId = message.id
            return result
        } catch let stickerError as StickerError {
            return failure(invocation, stickerError.errorDescription ?? String(localized: "这次没发成"))
        } catch {
            log("[tool] sticker send 失败：\(error.localizedDescription)")
            return failure(invocation, String(localized: "这次没发成"))
        }
    }

    private func stickerMake(
        _ invocation: ToolInvocation,
        persona: PersonaEntity
    ) async -> ToolResult {
        do {
            let sticker = try await stickers.makeSticker(
                url: invocation.argument("url"),
                baseStickerId: invocation.argument("baseStickerId"),
                text: invocation.argument("text"),
                overlayStickerId: invocation.argument("overlayStickerId"),
                position: invocation.argument("position"),
                personaId: persona.id
            )
            return success(
                invocation,
                title: String(localized: "做好了表情"),
                subtitle: sticker.displaySummary,
                detail: String(format: String(localized: "新表情 id：%@"), sticker.id)
                    + "\n" + sticker.displaySummary,
                modelText: "做好了（id=\(sticker.id)，\(sticker.displaySummary)）。"
                    + "它已经存进你的表情库；要发出去就用 sticker 的 send 动作，别忘了只有发了对方才看得到。"
            )
        } catch let stickerError as StickerError {
            return failure(invocation, stickerError.errorDescription ?? String(localized: "这次没做成"))
        } catch {
            log("[tool] sticker make 失败：\(error.localizedDescription)")
            return failure(invocation, String(localized: "这次没做成"))
        }
    }

    private func stickerSave(_ invocation: ToolInvocation) async -> ToolResult {
        let messageId = (invocation.argument("fromMessageId") ?? "")
            .trimmingCharacters(in: .whitespacesAndNewlines)
        if messageId.isEmpty {
            return failure(invocation, String(localized: "没有说是哪条消息里的表情（需要 fromMessageId）"))
        }
        do {
            let result = try await stickers.save(fromMessageId: messageId)
            let title = result.alreadySaved
                ? String(localized: "已经在收藏里了")
                : String(localized: "已收藏表情")
            return success(
                invocation,
                title: title,
                subtitle: result.sticker.displaySummary,
                detail: String(format: String(localized: "表情 id：%@"), result.sticker.id)
                    + "\n" + result.sticker.displaySummary,
                modelText: result.alreadySaved
                    ? "这个表情早就在你的收藏里了（id=\(result.sticker.id)），不用再收一次。"
                    : "已收藏（id=\(result.sticker.id)）。以后想发就用这个 id；收藏本身不会发消息。"
            )
        } catch let stickerError as StickerError {
            return failure(invocation, stickerError.errorDescription ?? String(localized: "这次没收成"))
        } catch {
            log("[tool] sticker save 失败：\(error.localizedDescription)")
            return failure(invocation, String(localized: "这次没收成"))
        }
    }

    /// 读一个可能写成数字、字符串或浮点的整型参数（模型经常把 1794500000000 写成字符串）。
    static func int64Argument(_ invocation: ToolInvocation, _ name: String) -> Int64? {
        guard let object = argumentsObject(invocation.argumentsJson) else { return nil }
        if let number = object[name] as? NSNumber { return number.int64Value }
        if let text = object[name] as? String {
            return Int64(text.trimmingCharacters(in: .whitespacesAndNewlines))
        }
        return nil
    }

    // MARK: - 听语音 / 看视频（0.13，`spec/audio-video/README.md`）

    /// 一段可播放媒体在聊天记录里的位置（工具回灌时要说清「是哪一条消息里的」）。
    private struct FoundMedia {
        var messageId: String
        var attachment: MessageAttachment
    }

    /// 在工作里找最近的一段语音/视频（不看历史远端，只看本机缓存最近 50 条）。
    ///
    /// - Parameter requestedId: 模型给的 `audioId` / `videoId`；空 = 取最近那一段。
    /// - 匹配方式：`mediaRefId`（服务端 id 或本地文件名主干）或 `uri` 后缀；
    ///   模型有时会把整条 `file://` 路径抄回来，所以两种都认。
    private func findRecentMedia(
        sessionId: String,
        wantAudio: Bool,
        requestedId: String?
    ) -> FoundMedia? {
        let requested = requestedId?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        // 0.14：拉黑期间的消息模型看不到，里面的语音/视频也不该被「听/看」到。
        let recent = (try? stores.messages.recentVisible(
            sessionId: sessionId, limit: Self.listenScanLimit
        )) ?? []
        for message in recent.reversed() {
            for attachment in AttachmentsCodec.decode(message.attachmentsJson) {
                guard wantAudio ? attachment.isAudio : attachment.isVideo else { continue }
                if requested.isEmpty {
                    return FoundMedia(messageId: message.id, attachment: attachment)
                }
                let id = attachment.mediaRefId ?? ""
                if id == requested || attachment.uri == requested
                    || attachment.uri.hasSuffix("/" + requested) {
                    return FoundMedia(messageId: message.id, attachment: attachment)
                }
            }
        }
        return nil
    }

    /// `listen`：把这段语音**真的**交给模型听一遍（`input_audio`），不是转写。
    ///
    /// 失败要给一句人话：没语音、id 找不到、文件已不在本机、格式不支持。
    /// 绝不能回「我听到了…」——模型没收到音频时那样说就是伪造。
    private func listen(_ invocation: ToolInvocation, sessionId: String) async -> ToolResult {
        let requested = invocation.argument("audioId")
        guard let found = findRecentMedia(sessionId: sessionId, wantAudio: true, requestedId: requested)
        else {
            let message = (requested?.isEmpty == false)
                ? String(localized: "没找到这段语音（id 可能不对）")
                : String(localized: "这里还没有语音可以听")
            return failure(invocation, message)
        }
        let attachment = found.attachment
        guard let data = audioVideo.data(for: attachment) else {
            return failure(invocation, String(localized: "这段语音已经不在本机了，听不到"))
        }
        guard let format = WireAudioPart.format(forMime: attachment.mime) else {
            return failure(
                invocation,
                String(format: String(localized: "这种语音格式还不支持（%@）"), attachment.mime)
            )
        }
        let durationText = attachment.durationText
        let parts = MediaWireParts(audio: [
            WireAudioPart(base64: data.base64EncodedString(), format: format)
        ])
        return ToolResult(
            invocation: invocation,
            ok: true,
            summary: durationText.isEmpty ? String(localized: "听了一遍") : durationText,
            contentForModel: "我听了一遍这段语音（时长 \(durationText)，\(Self.byteText(data.count))）。"
                + "声音本身已经作为音频片段交给你了：按你真正听到的内容回答，"
                + "不要把它当成文字转写，也不要假装听到了不存在的话；没听清就直说。",
            traceTitle: String(localized: "听了一遍"),
            traceDetail: String(format: String(localized: "语音 %@ · %@"), durationText, Self.byteText(data.count)),
            mediaParts: parts
        )
    }

    /// `watch`：抽 ≤8 帧关键画面交给模型（多张 `image_url`），并说明「N 帧 / 时长」。
    private func watch(_ invocation: ToolInvocation, sessionId: String) async -> ToolResult {
        let requested = invocation.argument("videoId")
        guard let found = findRecentMedia(sessionId: sessionId, wantAudio: false, requestedId: requested)
        else {
            let message = (requested?.isEmpty == false)
                ? String(localized: "没找到这段视频（id 可能不对）")
                : String(localized: "这里还没有视频可以看")
            return failure(invocation, message)
        }
        let attachment = found.attachment
        guard let url = audioVideo.fileURL(for: attachment) else {
            return failure(invocation, String(localized: "这段视频已经不在本机了，看不了"))
        }
        let count = VideoFramePolicy.frameCount(durationSeconds: attachment.durationSeconds)
        let rawFrames = await videoFrames.frames(
            of: url, count: count, maxPixel: VideoFramePolicy.maxPixel
        )
        let frames = VideoFramePolicy.budgeted(rawFrames)
        guard !frames.isEmpty else {
            return failure(invocation, String(localized: "这段视频的画面读不出来"))
        }
        let duration = attachment.durationSeconds
            ?? VideoFramePolicy.estimatedDuration(frames.count)
        let note = VideoFramePolicy.note(frameCount: frames.count, durationSeconds: duration)
        let images = frames.map { "data:image/jpeg;base64,\($0.base64EncodedString())" }
        return ToolResult(
            invocation: invocation,
            ok: true,
            summary: note,
            contentForModel: "我看了这段视频的 \(frames.count) 帧（\(note)）。"
                + "画面已经作为图片交给你了：按你看到的回答，"
                + "没看到的部分（前后被剪掉、画面外、声音）就说不知道，不要编。",
            traceTitle: String(localized: "看了几帧"),
            traceDetail: note,
            // 帧数/时长已经写在 `contentForModel` 里（回灌时会一起交给模型），
            // 这里不再重复一段 note，免得同一句「视频：N 帧」在请求里出现两遍。
            mediaParts: MediaWireParts(images: images)
        )
    }

    /// 工具扫描最近几条消息找媒体（一屏的聊天量足够：语音/视频都是「刚发来的」）。
    static let listenScanLimit = 50

    static func byteText(_ bytes: Int) -> String {
        if bytes >= 1024 * 1024 {
            return String(format: "%.1f MB", Double(bytes) / (1024 * 1024))
        }
        return String(format: "%d KB", max(1, bytes / 1024))
    }

    // MARK: - 这一轮不回（stay_silent）

    /// 「这一轮就不回」（`spec/mood/README.md` §4）：不落消息、不留念头、只标已读。
    ///
    /// 真正的拦截在 `ChatService`（拿到 `stay_silent` 就直接结束这一轮）；这里实现一份是为了
    /// 让「任何一处调它」语义一致，也为了让工具注册表与执行器保持一一对应
    ///（`ToolExecutorTests` 守着「清单里的每个工具都有人认识」）。
    ///
    /// 这里**什么都不写库**：连续沉默的计数在 `ChatRhythmService` 里（本机 UserDefaults），
    /// 不在工具层。
    private func staySilent(_ invocation: ToolInvocation, persona: PersonaEntity) -> ToolResult {
        let reason = StaySilent.cleanReason(invocation.argument("reason"))
        _ = persona
        _ = reason
        return ToolResult(
            invocation: invocation,
            ok: true,
            summary: String(localized: "这轮先不回"),
            contentForModel: "这一轮你选择只看一眼、不回话。不再产生任何消息，也不用解释。",
            traceTitle: String(localized: "这轮没说话")
        )
    }

    // MARK: - 先不回

    /// 这一轮选择不回。
    ///
    /// 三件必须一起成立的事（规格 §四）：不产生助手消息、用户消息已读、留一条 REACH_OUT 念头。
    /// 真正的「不发消息」由 `ChatService` 在拿到 `defer_reply` 时直接拦截；这里照样实现一份，
    /// 保证「任何一处调它，落库语义都一致」。
    private func deferReply(
        _ invocation: ToolInvocation,
        persona: PersonaEntity,
        sessionId: String,
        now: Int64
    ) async -> ToolResult {
        // 0.11.0：夹取 / trim / 窗口计算走 agent 决策层（`/v1/core/defer-plan`）；
        // 失败时用本机 `DeferReply.plan` 兜底（工具执行不能失败）。
        let minutes = invocation.intArgument("minutes", default: DeferReply.defaultMinutes)
        let reason = invocation.argument("reason")
        let plan: DeferPlan
        do {
            // 0.11.1 审计 P2-1：严格模式失败 → 这条工具**可见地失败**（trace 里能看到原因），
            // 而不是悄悄用本机结果糊过去。普通回退模式照旧（`decide` 内部回退本机）。
            plan = try await agentCore.decide(
                endpoint: "defer-plan",
                nativeFallback: AgentCoreNativeFallback.deferPlan(
                    minutes: minutes, reason: reason, now: now
                )
            ) {
                try await agentCore.deferPlan(minutes: minutes, reason: reason, now: now)
            }
        } catch {
            log("agentcore: defer-plan 严格模式失败（\(error)），这一轮不落「先不回」")
            return failure(
                invocation,
                String(localized: "决策服务不可用，这次先不做了")
            )
        }
        let window = AgentWindow(
            earliestAt: plan.earliestAt,
            latestAt: plan.latestAt,
            note: plan.reason.isEmpty ? "刚才没说话，过一会儿再看看" : plan.reason,
            hint: "到点以后你自己决定要不要开口；不想说也可以继续沉默"
        )
        _ = intents.plan(
            personaId: persona.id,
            window: window,
            kind: .reachOut,
            note: window.note,
            hint: window.hint,
            sessionId: sessionId,
            now: now
        )
        // 他确实看过了，只是选择不回；「已读」是唯一该出现的反应。
        _ = try? stores.messages.markUserMessagesRead(sessionId: sessionId, at: now)
        return success(
            invocation,
            title: String(localized: "决定先不回"),
            subtitle: nil,
            detail: String(
                format: PluralText.template("过 %d 分钟再想这件事%@", plan.minutes),
                plan.minutes,
                plan.reason.isEmpty ? "" : String(format: String(localized: "（%@）"), plan.reason)
            ),
            modelText: "好的，这一轮不回复。不要再输出任何文字，也不要解释；对方会看到「已读」，过了 \(plan.minutes) 分钟你会再想起这件事。"
        )
    }

    // MARK: - 辅助

    /// 走 agent 决策层夹一次工具参数（0.11.0）。
    ///
    /// 参数表原样传过去（`tool-args` 的契约是「只夹已知数值键、其余键原样保留」），
    /// 回来再取要用的那一个数字。远程失败时用本机兜底并记一行日志：
    /// 工具执行失败会让模型以为「这件事做不了」，比慢一个超时更糟。
    private func clampedToolArgs(
        _ tool: String,
        invocation: ToolInvocation
    ) async throws -> [String: JSONValue] {
        let args = AgentCoreJSON.object(fromArgumentsJson: invocation.argumentsJson)
        // 严格模式失败会抛出来，由调用点变成一条可见的工具失败（0.11.1 审计 P2-1）；
        // 普通回退模式由 `decide` 静默用本机夹一次。
        return try await agentCore.decide(
            endpoint: "tool-args",
            nativeFallback: AgentCoreNativeFallback.clampToolArgs(tool: tool, args: args)
        ) {
            try await agentCore.clampToolArgs(tool: tool, args: args)
        }
    }

    private func success(
        _ invocation: ToolInvocation,
        title: String,
        subtitle: String?,
        detail: String,
        modelText: String
    ) -> ToolResult {
        ToolResult(
            invocation: invocation,
            ok: true,
            summary: String((subtitle ?? title).prefix(40)),
            contentForModel: modelText,
            traceTitle: title,
            traceDetail: detail
        )
    }

    private func failure(_ invocation: ToolInvocation, _ message: String) -> ToolResult {
        ToolResult(
            invocation: invocation,
            ok: false,
            summary: message,
            contentForModel: "这次调用没成功：\(message)。用你已经知道的东西继续，不要向对方解释工具细节。",
            errorMessage: message,
            traceTitle: ToolProtocol.describe(ToolResult(
                invocation: invocation, ok: false, summary: message, contentForModel: "", errorMessage: message
            )).title,
            traceDetail: message
        )
    }

    static func userFacing(_ error: Error) -> String {
        if let api = error as? ApiException { return api.message }
        if let failure = error as? CompletionFailure { return failure.message }
        let localized = (error as NSError).localizedDescription
        return localized.isEmpty ? String(localized: "工具调用失败") : localized
    }

    /// 参数 JSON → 对象（坏数据返回 nil，不抛）。
    static func argumentsObject(_ raw: String) -> [String: Any]? {
        let text = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let data = (text.isEmpty ? "{}" : text).data(using: .utf8),
              let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            return nil
        }
        return object
    }

    /// 任意 JSON 值 → 字符串（对象按键名排序，字符串原样）。与 `ToolProtocol.argumentsJson(from:)` 同语义。
    static func jsonString(_ value: Any) -> String {
        if let text = value as? String { return text }
        if JSONSerialization.isValidJSONObject(value),
           let data = try? JSONSerialization.data(withJSONObject: value, options: [.sortedKeys]),
           let text = String(data: data, encoding: .utf8) {
            return text
        }
        return ""
    }
}
