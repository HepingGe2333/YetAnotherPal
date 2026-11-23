# 更新记录

> English version: [CHANGELOG.en.md](CHANGELOG.en.md)

版本号规则见 [CONTRIBUTING.md](../CONTRIBUTING.md)：内测阶段主版本固定为 `0`，小改动末尾 +1，大改动中间 +1。

> 编号说明：`0.9.0` 之前的版本用的是旧编号（`1.0.0`–`1.8.0`）。开源发布时把当时的最新版 `1.9.0`
> 改成了 `0.9.0`，更早的版本保留旧编号只在下面这张表里留档。

| 版本 | 主要变化 |
| --- | --- |
| `1.0.0` | 人格、会话、流式回复、Markdown / LaTeX、长期记忆雏形 |
| `1.1.0` | 人格版本快照、预设库、主动唤醒提案制、图片消息 |
| `1.2.0` | 记忆生命周期与每日整理（还在用 / 沉下去了 / 已归档） |
| `1.3.0` | `recall` 三档检索、回忆重构（回忆会改变记忆） |
| `1.4.0` | 人设卡与记忆筛查、自主决策协议与时间窗口、回复系统通知 |
| `1.5.0` | 情绪漂移、在场感（他此刻在做什么） |
| `1.6.0` | 备份与恢复（合并 / 替换 / 预览）、用量记账 |
| `1.7.0` | 消息里的链接与内置浏览器、心跳 U 形曲线、本地向量检索、长会话滚动摘要 |
| `1.8.0` | 移除原生 Harness 链路、iOS 端跑通、备份双向互通 |
| `0.9.0` | 把设置项从产品里拿掉：一人一段会话、已读回执、`defer_reply`、睡眠与唤醒阈值、人格细分字段、并发闸 ≤3、只统计 token |
| `0.9.1` | 两端可选头像（自己 + 他）、iOS 补齐落后 Android 的能力、内存占用优化 |
| `0.10.0` | 英文 UI 与双语资源守卫、跨端 agent 契约用例与 agentd 服务骨架、文档中英双语化 |

---

## 项目更名为 YetAnotherPal（Yap）

项目正式更名为 **YetAnotherPal**，缩写 **Yap**。仓库地址改为
<https://github.com/HepingGe2333/YetAnotherPal>，旧链接会由 GitHub 重定向。

- Android 应用 ID 改为 `com.yap.app`；iOS Bundle ID 改为 `com.yap.app.ios`。
- iOS Swift 模块改为 `YapKit`，Xcode 工程改为 `YetAnotherPal.xcodeproj`。
- 备份格式字符串 **`myfriend.backup` 保持不变**，保证历史备份仍可读取；格式名是协议标识，不随品牌更名。
- 为便于检索，本文历史条目中的产品名、包名、路径与测试 target 已统一为当前命名；唯一保留的旧字符串是兼容格式名
  `myfriend.backup`。




## 0.14.2（内存优化，开发中）

- **桌面/电脑端 agentd**：新增 `agentd/scripts/run-low-memory.sh`，固定 `-Xmx384m`、Metaspace 96m、
  CodeCache 64m、直接内存 64m、SerialGC、C1；避免 16GB 机器默认按 1/4 物理内存选 4GB 堆。
  实测启动 RSS **约 78MB**（空闲），预算内 <500MB。
- **Android 本地执行 ≤256MB**：图片内存缓存从 `min(可用×12%, 48MB)` 收紧为 `min(可用×8%, 24MB)`；
  新增表情帧 24MB、视频帧 12MB 预算常量与 `decodingCachesWithinBudget()`；
  `YapApplication.onTrimMemory/onLowMemory` 在系统压力下清 Coil 内存缓存；manifest 明确 `largeHeap=false`。
- 新增/更新 `AppMemoryBudgetTest`；Android 单测 **2074 / 0 失败**，agentd **668 / 0 失败**。
- 说明：移动端 256MB 是**预算/上限设计**，尚未在低端真机上用 Memory Profiler 实测；下一步需真机验收。

## 0.14.0（开发中）

- **正在输入不再显示「…」占位气泡**：只显示输入指示，失败/停止不产生空消息。
- **AI 拉黑**：`block_user` 工具（1–24 小时、两轮确认、10 分钟过期）；拉黑期间用户消息红色感叹号 +
  「对方已将你拉黑」，模型上下文永久排除这些消息，到期自动解除。
- **心理活动**：活跃窗口（10 分钟/刚发消息）每 3 分钟或 3 轮生成 ≤60 字私念并微调心情
  （每维 ≤1）；激动/变化≥2 必做；念头注入下一轮提示词。
- **连发与节奏**：`send_message` 每轮最多 4 条短消息，间隔 0.8–2.5s；空行自动拆 1–3 条；
  无需一问一答，活跃窗口持续触发。
- **打字延迟**：`clamp(0.4 + 字数×0.025 + 心情修正, 0.4, 4.0)` 秒。
- **记忆**：审计并修复召回（空查询/去重/人格隔离/截断/稳定排序）；新增 `session_memory`
  短期（每 5 条、≤200 字）与长期（每 30 条、≤500 字）摘要，作为「你的备忘录」注入。
- **schema v18**：`personas.blockedUntil/blockReason/blockProposalJson`、`messages.blocked`、
  `mental_activities`、`session_memory`；两端同构。
- **服务端同步**：工具回路新增 `block_user`/`send_message`，服务端独立执行拉黑/连发/心理活动/记忆；
  SSE 为多条 `message.created` + 单个 `done`。
- 测试：Android 2040/199 类、iOS 2120（2 skip）、agentd 602、agentcore 90。

## 0.13.0

发布：0.13.0 / 构建号 20（Android 与 iOS）；服务端可独立运行、备份 v6 与客户端互通。

- **每轮注入当前时间**：聊天/主动/工具回路每次请求现取本地时间（日期+周X+HH:mm+UTC 偏移），不缓存、不重复追加。
- **模型 API 默认参数**：温度 0.75、上下文上限 1,000,000、单次最大输出 16,384；配置显式值优先。
- **日记板 `diary` 工具**（schema v15）：模型可添加/翻看/删除自己的备忘，支持 `remindAt`/`remindInMinutes` 定时提醒；
  到点由心跳变成念头，只触发一次、错过 >24h 不补；两端表结构一致并有迁移/对照测试。
- **提示词框架补全**：新增「# 你能做什么」段，由工具注册表生成（用途/副作用/区别句），
  `set_activity`≠睡觉、`set_quiet_hours`=睡觉、`diary`=自己的备忘录等；契约测试保证与注册表一致。
- **人格进化**：每 20 条用户消息或 24h 让模型提议 ≤3 条变化（只允许 traits/likes/behaviors/quirks，
  置信度 ≥0.6，去重），进入「他最近的变化」待审；接受才写回并 version+1，7 天过期丢弃。
- **表情包**：导入图片/GIF/WebP/APNG 自动压缩为 GIF（≤512KB、长边 320、≤20fps、≤60 帧、≤6s），
  独立表情按钮 + 历史面板（最近 30/全部），点选即发送、长按收藏；模型 `sticker` 工具支持发送/制作（下载图片、
  基底+文字、基底+叠图、位置）/收藏用户表情；服务端模式经 `/v1/media?kind=STICKER` 上传。
- **声音与视频**：按住录音（AAC 16kHz、≤5 分钟、≤8MB）与选视频（≤2 分钟/64MB，不转码）；
  消息附件新增 AUDIO/VIDEO 与时长，气泡可播放；模型通过多模态 `input_audio` / 视频关键帧**真正听到声音、看到画面**，
  并新增 `listen(audioId?)` / `watch(videoId?)` 工具；不支持的模型明确报错，不伪造转写。
- **切换云端需同意**：本地→远程弹窗（同意并同步 / 只同步之后的新内容 / 取消）；同意后逐项压缩上传历史媒体
  （图片/表情/音频/视频，失败计数、可取消），拒绝则不切换；云端→本地先做一次全量下载并校验条数，失败保持云端。
- **提示词工具段统一**：两端「# 你能做什么」按同一规范生成（`- 工具名(必填, 可选?)：用途` + `· 区别：…`）。
- **人格进化云端可用**：服务端模式回复完成后同样按 20 条/24h 触发评估，接受时 `POST /v1/personas` 写回服务端并同步本地。
- **服务端媒体资源同步**：拉取到 `/v1/media/<id>` 时按 kind 下载到本机持久目录（图片/头像/表情/音频/视频），
  附件改写为本地路径并保留 `serverMediaId`；幂等、失败占位计数；渲染/播放直接用本地文件。
- **云端情绪与人格进化**：服务端模式回复后调用 `/v1/personas/{id}/mood/evaluate`（4 条节流）与
  `evolution/evaluate`、`pending`、`accept|reject`；接受以服务端回执写本地缓存；未配置 LLM 时静默跳过。
- **本机服务端使用客户端模型配置**：客户端在连接回环地址的服务端时 `POST /v1/llm-config` 推送本机配置；
  远程服务端一律 403，必须由部署者用 `--llm-*` / `YAP_LLM_*` 配置。
- **备份 v6 全量**：媒体（图片/头像/表情/音频/视频）+ `stickers[]` + `diaryNotes[]`，导入还原文件与行；
  v1–v5 可读；8 MiB/张、128 MiB 总量，超限跳过计数。
- **视频/语音上传限制**：发送与上传前校验视频 ≤2 分钟/64 MiB、音频 ≤5 分钟/8 MiB，超限明确拒绝且不上传。
- **提示词实测（DeepSeek 官方 API）**：显式写「不想说话时调用 stay_silent，不要用文字代替；对方在说重要的事时不要用」后，
  模型在「晚安/在吗/吃了没」会只已读不回，在「被领导骂了」会正常回；该条已加入两端「# 别像 AI」段。
- **仍未做**：备份不携带表情/语音/视频二进制与日记；服务端模式下 `listen/watch` 需要 agentd 侧执行（本轮客户端已支持上传与本地模式工具）。
- 测试：Android 1568 / 153 类、iOS 1753（2 skip）、agentd 392、agentcore 90；两端构建通过。

## 0.12.1

小版本：**切换远程服务端时自动全量同步（双向）**、**新客户端自动拉取历史**、**用户头像上传**、**服务端备份流式化（内存有界）**。

- **双向同步**：首次连接某个服务端地址时自动判断四象限（两端空 / 服务端空→推送本地 / 本地空→拉取服务端 /
  两端都有→合并，冲突服务端优先）；本地人格/预设/记忆/设置/会话与消息（每 200 条/批）全量推送，
  新客户端倒序翻完所有块拉取历史并保留未读/已读/时间；完成后走 5 分钟增量同步 + 写路径推送。
- **历史消息导入 API**：`POST /v1/sessions/{id}/messages/import`（≤200 条/批、按 id 幂等、保留 createdAt/readAt/附件、
  不触发 LLM、不动未读）。
- **用户头像**：设置里选头像 → 压缩（≤512/400KB）→ `POST /v1/media?kind=USER_AVATAR` → `userAvatarUri`
  （服务端设置白名单，兼容旧键 `myAvatarUri`）；渲染带鉴权、离线回退本机缓存；本地模式不变。
- **服务端备份内存优化**：导出改为 chunked 流式（消息 k 路归并、媒体分块 base64，不再构造整份 JSON）；
  导入把请求体流式落临时文件后增量解析（媒体逐条解码落盘）；测试在 `-Xmx256m` 下完成 1 万条消息 + 50 张图的
  真 HTTP 往返，证明内存有界。
- **版本**：0.12.0 → 0.12.1，构建号 18 → 19；schema 仍 v14、备份 v5。

## 0.12.0

大改动：**共享决策层迁移到 Kotlin Multiplatform（iOS 复用同一份 Kotlin）**、**历史改为区块存储**、
**检索取消候选上限**、**备份 v3 带走图片与头像**；并清理了 README 里的「已知限制」清单。
版本 `0.12.0`，两端构建号 **18**；schema **v11**、备份 **v3**。
（本版在 `dev/0.12.0` 分支上开发，未合并到 `main`，未打发布 tag。）

### 共享决策层：Kotlin Multiplatform

- `agentcore/` 从 `kotlin("jvm")` 改为 `kotlin("multiplatform")`：`jvm()` + `iosX64()` + `iosArm64()` +
  `iosSimulatorArm64()`，产出静态 framework **`AgentCoreKit`**。`commonMain` 放两端共用的决策，`jvmMain` 只留
  真正依赖 `java.*` 的 5 个文件（后台锁、会话世代号、文件路径守卫、有界读、语言名），`PlatformTime` 用 expect/actual。
- `WebUrlPolicy` 原本依赖 `java.net.URI`，为进 `commonMain` **重写为纯字符串解析**（保留全部安全语义与 12 条契约测试）；
  `LocalEmbedding`/`VectorMath` 的手写小端字节序、日历算术、小顶堆等也都改成平台无关实现。
- **iOS App 的四个 agent 决策现在执行同一份 Kotlin 代码**（`KotlinAgentCoreClient` → `InProcessAgentCore`）；
  Swift 实现只保留为 macOS `swift test` 的回落与契约对照。模拟器实跑证明 Kotlin 与 `spec/agent-core` 的
  189 条断言一致，App 启动日志打印 `agent core: kotlin`。
- 构建：`ios/scripts/build-agentcore-framework.sh` 生成 `ios/.build/agentcore/AgentCoreKit.xcframework`；
  Xcode 工程加了 framework 搜索路径与 `-framework AgentCoreKit`（静态、Do Not Embed），并有一个
  Run Script 在 framework 缺失时自动构建。

### 历史存储：区块 + 双向分页

- schema **v11**：`messages.blockIndex` + `message_blocks(sessionId, blockIndex, first…, last…, messageCount)`，
  两端逐字一致（iOS 有 `SchemaParityTests` 直接对 Android Room 导出的 `11.json` 做列/索引对照）。
- `BLOCK_SIZE = 200`；写入走统一的块维护（满块开新块、按锚点插入、删除重算边界、空块删行留编号空洞、不重平衡），
  覆盖发送/回复/中断/失败/重新生成/编辑/删除/清空/删会话/删人格/备份导入/主动消息。
- **分页 = 区块**：首屏只读最新一块，向上/向下各加载一块（编号有空洞时按块表找实际相邻块），一页从不使用 offset；
  未读/已读/DND 仍走全表。
- 迁移 v10→v11 用 `ROW_NUMBER()/200` 回填并生成块行（流式，不整表读入内存）；两端都有「数据不丢、边界正确、
  幂等、空库可迁移、孤儿块清理」的迁移测试。

### 检索：无候选上限

- 旧的「`LIKE` 粗筛 + 500 条候选上限」被删除；改为**逐区块扫描**：枚举（可过滤人格/时间的）区块 → 每块读入内存 →
  `SearchRanking` 精排 → **键集游标**分页。峰值内存 ≈ 一块 + 一页 + frontier，与历史长度无关。
- 命中 >500 的用例已覆盖：620 条命中可完整翻页、最旧块里的命中不会丢、翻页期间新增消息不重复不乱序；
  「每次查询只读一块」有结构化断言。
- 代价写进 README：每翻一页都会重新走一遍区块，历史越长越慢（这是「无上限 + 无 offset」的直接后果）。

### 备份 v3：图片与头像随备份走

- 新增顶层 `media[]`（`id/kind/personaId/fileName/mime/bytes/data`，标准 base64）：人格头像、用户头像、
  预设头像与聊天图片都随备份走；`avatarUri` 与 `attachmentsJson` 只写裸文件名，导入时写入新 UUID 文件并重写引用。
- 限额：总量 128 MiB、单张 8 MiB、仅 JPEG/PNG/WebP；超限/坏数据逐条跳过并给出中英告警；导入上限 256 MiB。
- v1/v2 备份仍可读（没有 `media` 时按旧行为告警）；`fixtures/backup/` 新增 v3 黄金样本并双向互读。
- 媒体写入在数据库事务之前、事务失败回滚删除新文件；文件名与目录都过归属/穿越守卫。

### 文档与限制清单

- README 的「已知限制」重新核对：删掉**不是限制**的条目（仓库不含产物、只有 token 统计、远程默认关闭、
  发布包不明文 HTTP、模拟器 Keychain），把它们移到「设计立场」；删掉已被本版修复的条目（检索上限、分页代价、
  图片不进备份、iOS 第二份实现）；保留并写清真正的限制（无 CI、iOS 后台调度由系统决定、检索逐页重扫、
  双向浏览累积区块、不重平衡、备份图片限额）。
- `docs/ARCHITECTURE` 增加「历史存储：区块」与新的「检索」小节、把 agent 核心改为 KMP 四形态；
  `docs/README` 索引补 `spec/messages-blocks`；`CONTRIBUTING` 增加「先构建 AgentCoreKit 再构建 iOS」的步骤，
  数据/备份改动清单更新到 v11 / v3。

### 测试与构建

| 目标 | 结果 |
| --- | --- |
| Android | **1044 条 / 0 失败**（另有 28 条 instrumented：迁移 v10→v11、块维护） |
| `:agentcore`（KMP jvmTest） | **60 条 / 0 失败** |
| `:agentd` | **34 条 / 0 失败** |
| iOS | **1263 条 / 0 失败**（Debug/Release 构建零 Swift 警告） |
| 构建 | `assembleDebug/Release`、`lintDebug`、`:agentd:fatJar`、iOS Debug/Release、`AgentCoreKit.xcframework` 全部成功 |

---

### 0.12 续 1（未升版本）：CI 门禁、第三方搜索 provider、schema v12 / 备份 v4、媒体压缩、UI 行为测试与大库基准

大改动：**CI 门禁**、**`web_search` 支持第三方搜索 API（schema v12 / 备份 v4）**、**媒体导入与存储压缩**、
**两端 UI 行为测试**、**大库性能基准**、**agentd 限流与 TLS 部署说明**，并把 iOS 的 Swift 第二实现收窄到「仅 macOS 测试」。
仍未升版本（用户未批准版本变更）：两端仍是 `0.12.0` / 构建号 **18**；schema **v12**、备份 **v4**。仍在 `dev/0.12.0` 分支迭代，未合并 `main`、未打 tag。

### CI（GitHub Actions）

- 新增 `.github/workflows/ci.yml`：
  - **Linux**：JDK 17 + Gradle，跑 `:app:testDebugUnitTest`、`:agentcore:test`、`:agentd:test`、`assembleDebug`、
    `:agentd:build`、`lintDebug`，失败时也上传测试报告与 lint 结果；
  - **macOS**：先 `ios/scripts/build-agentcore-framework.sh` 生成 `AgentCoreKit.xcframework`，再
    `swift test --disable-sandbox`，最后 `xcodebuild build-for-testing`（绕过 UI 测试）；缓存 `~/.gradle`、`~/.konan`、
    SwiftPM 构建目录；`CODE_SIGNING_ALLOWED=NO`；
  - 触发 `push(main, dev/**)` 与 `pull_request(main)`，`concurrency` 取消旧运行，另有 `ci-ok` 汇总 job 作为 required check。
- `CONTRIBUTING` 补「CI 会跑什么、本地怎么复现」；README 的「已知限制」里删掉了「没有 CI」。

### `web_search`：第三方搜索 API

- 新增 `spec/web-search/README.md`（规范性）：`api_configs.webToolsProvider`（`MESSAGEAPI` / `TAVILY` / `BRAVE` /
  `SERPER` / `GENERIC`）、默认 base URL、各 provider 的请求/响应形状、归一化结果、`web_fetch` 行为、错误与限额。
- 两端实现同一套 override 语义：`DEEPSEEK` 保持原服务端 search/fetch；其余 provider 走各自 API；`web_fetch` 在非
  DeepSeek 时由客户端抓取（10s、≤2MiB、去标签、≤8000 字）。缺 Key 明确报错，401/403/429/5xx 带 provider 名，
  原始 body 只截断进日志；所有 URL 发请求前过 `WebUrlPolicy`。
- 设置页「API 配置」新增「搜索服务」选择、随 provider 变化的地址占位/提示、Key 复用既有 `webToolsApiKeyCipher`
  （加密本机、不进备份）；GENERIC 必填地址、可选 Key。
- **schema v12**：`api_configs.webToolsProvider`（默认 `DEEPSEEK`），两端迁移 + 迁移测试 + schema 对照测试。
- **备份 v4**：`apiConfigs[].webToolsProvider`（默认 `DEEPSEEK`，导出总是写），Key 仍不进备份；v1–v3 可读；
  `fixtures/backup/{android,ios}-export-v4.json` 生成并双向互读。
- `harness/server.mjs` 增加各 provider 的 mock 分支，便于本地联调。

### 媒体压缩（导入与存储）

- 备份导入与选图共用同一套压缩：头像长边 ≤512 / ≤400KB，聊天图片长边 ≤1600 / ≤800KB，JPEG 质量阶梯 0.85→0.50，
  透明图先垫白底；坏图/压不进上限逐条跳过并告警，不让整份导入失败。
- 内容寻址去重：文件名带 SHA-256 前 16 位，同内容不重复落盘（不新增表）；头像保留「一文一文件」避免换头像误删共享文件。
- 原有 8 MiB/张、128 MiB 总量输入闸保持；v3 备份里的图片仍可导入。

### 测试与基准

- **两端 UI 行为测试**（新增）：
  - Android：`app/src/androidTest/.../ui/` 6 条 Compose 行为测试（首屏只读一块、向上/向下加载、跳转定位高亮、
    发送失败保留草稿、清空与整库删除后的行为），新增 `HiltTestRunner`、Compose UI 测试依赖，debug 变体不再注册
    `BootReceiver`（避免安装后广播在 Hilt 启动前崩溃）；
  - iOS：`YetAnotherPalTests/ChatUiBehaviorTests` 6 条（同口径），在模拟器上通过（本沙箱用 `simctl spawn xctest` 运行，
    `xcodebuild test` 受 pty 限制）。
- **大库基准**：Android/iOS 各一套 benchmark（10 万条消息迁移、检索第 1/10/100 页、备份 500 图峰值内存），
  数字与结论见 `docs/performance.md`。
- 顺带修复：agentd 一条 socket 测试在并行构建下的偶发失败（等 EOF 改为超时即用已收内容）。

### 平台与安全

- **agentd 限流**：按来源 IP 令牌桶（默认 60/分钟、突发 30，可 `--rate-limit` / `--rate-burst` / env 配置，
  0 = 关闭），超限 429 + `Retry-After`；`/health` 独立桶保证探针可用；限流先于鉴权，超限请求不读 body。
- **TLS 部署说明**：agentd 只提供明文 HTTP，README 给出 Caddy/Nginx 反代终止 TLS 的最小配置、`X-Forwarded-For`
  不信任的说明与「不要把 token 关掉」的建议。
- **iOS Swift 第二实现收窄**：`NativeAgentCoreClient` 整体放进 `#if !os(iOS)`；iOS App 运行时只有 Kotlin
  （构建产物 `strings` 里已无该符号），Swift 实现只服务 macOS `swift test`；新增源码守卫测试防止回流。
- **版本号守卫钉回精确值**：`versionCode = 19` / `versionName = 0.13.0` / `CURRENT_PROJECT_VERSION = 19` /
  `MARKETING_VERSION = 0.13.0`（Debug/Release），Android 与 iOS 各一条精确断言 + 结构断言。
- **Android 与 iOS 对齐「删除全部数据后重新播种」**：Android 之前会保留「已播种」标记导致空白首页，现在清空后按
  当前语言重新播种三个人格（`resetAll(preserveSeedMarker = false)` + `seedIfNeeded()`）。

### 测试与构建

| 目标 | 结果 |
| --- | --- |
| Android | **1085 条单元测试 / 97 类 / 0 失败**（媒体引用计数 +13）；`instrumented` 已编译，设备批次留待统一运行 |
| `:agentcore`（KMP jvmTest） | **60 条 / 0 失败** |
| `:agentd` | **53 条 / 0 失败** |
| iOS | **1315 条 / 0 失败**（2 条 benchmark 默认跳过；Debug/Release 零 Swift 警告） |
| 构建 | `assembleDebug/Release`、`lintDebug`、`:agentd:build`、iOS Debug/Release、`AgentCoreKit.xcframework` 全部成功 |

---

### 0.12 续 2（未升版本）：搜索 provider 更名 MessageAPI、真实接口实测、媒体引用计数

- **`DEEPSEEK` → `MESSAGEAPI`**：这个 provider 指向 `/v1/tools/search`、`/v1/tools/fetch` 这套 tools 接口，
  任何提供同一套接口的厂商都能用，所以改名为 `MESSAGEAPI`（DeepSeek 官方是默认实现）。**读旧值、写新值**：
  旧库/旧备份里的 `DEEPSEEK`（含大小写、空白）在读取时归一化，写库/写备份一律 `MESSAGEAPI`；
  数据库列默认值保持 `'DEEPSEEK'`（v12 已装机库不能改，否则 schema 校验失败）。
- **真实接口实测**：`docs/web-search-live-check.md` 记录 MessageAPI（DeepSeek 官方）与 Tavily 的真网调用
  （状态码、延迟、返回字段形状、401 行为），Brave/Serper 因网络原因未测。两个 provider 的解析与规范一致。
- **媒体引用计数**（修「去重后删文件误删共享图」）：两端新增 `MediaReferences` 查询与单点守卫
  `deleteIfUnreferenced`，所有删文件路径改为「先删数据库行 → 查引用 → 零引用才删文件」：删消息/会话/清空上下文/
  清空聊天/删人格/删预设/换头像/换用户头像/备份替换导入/清空全部数据/草稿图。不新增表、不升 schema。
- **本地检索说明**：`docs/local-search.md` 写清本地搜索的框架（纯 Kotlin + SQLite 逐块扫描，无 FTS/无第三方检索库）、
  能力矩阵与明确边界，以及升级路线。



### 0.12 续 3（未升版本）：服务端权威 P1 —— 存储/状态/SSE/搜索/主动 + 两端传输层

目标是把客户端变成「只做 UI 与交互」，状态与逻辑上移到 `agentd`，让 iOS/Android 不需要后台进程也能持续运行
（设计契约：`docs/design/server-authoritative.md`）。

- **服务端存储与 API**：`--data` 文件存储（原子写、单写锁、区块维护、事件游标、secrets 0600）、
  `GET /v1/state`、`/v1/sessions`、区块双向分页、`POST /v1/sessions/{id}/messages`（SSE，
  `clientMessageId` 幂等、客户端断开仍生成并落库）、`GET /v1/events`（长轮询）。
- **服务端搜索**：`MESSAGEAPI` / `TAVILY` / `SEARXNG`（自建，无 key）/ `BING` 与
  `ZHIHU` / `TOUTIAO` / `QUORA` 站点过滤源；`POST /v1/tools/web-search`、`/v1/tools/web-fetch`；
  服务端配置 key（`YAP_AGENTD_SEARCH_*`），客户端不传密钥。
- **服务端主动**：`ProactiveEngine`（睡眠/DND/冷却/每日上限/概率，沿用 `spec/agent-core`）、
  定时器不依赖客户端连接、`POST /v1/proactive/tick`（调试/测试入口）。
- **客户端传输层**：Android `data/remote/`、iOS `Net/Server/`，各自的 `ServerConfig`（https 必须、debug 才允许
  本机明文）、`ServerApi`、增量 SSE 解析、取消即关连接、状态映射器；**尚未接 UI**（P1b）。
- **测试**：全量批次 —— Android **1139 条 / 101 类 / 0 失败**（+54）、`:agentcore` 60、`:agentd` **244**（+17 路由用例）、iOS **1386 条 / 0 失败**（+71，2 条 benchmark 默认跳过）；两端 Debug/Release 与 lint 全过。


### 0.12 续 4（未升版本）：人设引导 —— 世界预设、话风学习、占用时间工具、提示词重排

本轮把「模型像不像这个人」从「一段人设描述」升级成「身份 + 世界 + 此刻状态 + 话风 + 关系 + 边界」，
并把提示词在本机 DeepSeek 官方 API 上实测过（契约与实测记录：`spec/persona-guidance/README.md`）。

- **世界预设（World Preset）**：角色所处的世界/城市、时代、日程、人际关系、必须承担的事；schema v13
  （`personas` 与 `persona_presets` 新增 `worldJson`）、备份 v5；预设编辑器新增「世界设定」卡（可手填 + AI 补全）。
- **话风学习**：粘贴一段微信/QQ 聊天记录 → LLM 总结出 `tone` / `phrases` / `habits` / `avoid`（提示词实测输出合法 JSON）；
  无 API Key 或失败时走本地兜底（短句频次 + 占位风格），**原文不落库、不进日志、不进备份**。
- **`set_activity` 工具**：模型可以声明自己接下来一段时间在做什么（非睡眠）：`kind/activity/minutes/availability`，
  `BUSY` 时主动消息不开口、回复更短、明确说几点后再聊；`LIMITED` 降概率；到期自动失效。
- **提示词重排**：`# 你是谁 → # 你所在的世界 → # 你此刻的状态 → # 你怎么说话 → # 你们的关系 → # 边界`；
  强制第一人称、不旁白、不总结人设、不替用户决定、常用语最多自然带一处。两端 `PromptBuilder` 同步。
- **备份 v5**：`personas[]` 带世界/话风/活动，`presets[]` 带世界/话风；v1–v4 仍可读。
- 顺带修复上一轮遗留：agentd 主动计数落盘（重启不丢、跨日重置、并发只发一条）、两端媒体删除 TOCTOU（同一把文件锁覆盖「写文件+引用」与「查引用+删文件」）、Android 主动规则也受 `BUSY` 门禁约束。
- 测试：全量批次 —— Android **1210 条 / 111 类 / 0 失败**、`:agentcore` 60、`:agentd` **249**、iOS **1453 条（2 skip）/ 0 失败**；两端 Debug/Release 与 lint 全过。


### 0.12 续 5（未升版本）：世界观可视化、人设翻译、服务端模式 M3/M4 与 CRUD

- **世界观可视化编辑器**：日程/关系改为行式编辑（时间选择器、关系下拉、亲密度），义务逐条，新增「其他背景（notes）」
  兜底自由文本（≤1200 字）；整合进人格预设界面（Android 预设页/人格页共用；iOS 新增预设列表与编辑页）。
- **人设提示词本地化**：schema v14（`personas.translationJson/translationLang`），进入聊天/主动生成前按需调用 LLM
  把人物与世界观翻译成软件语言；缓存按规范化 JSON 的 SHA-256 指纹失效（两端同一算法，共享 `hash-cases.json`），
  `phrases` 永不翻译；无 Key/失败/超时用原文，设置可关闭。
- **提示词脚手架唯一来源**：`:agentcore` 新增 `PersonaPromptScaffold`（与 `spec/persona-guidance/scaffold.json`
  逐字一致），两端 `PromptBuilder` 使用同一套中文措辞并各带契约测试；新增 `world_name` 行。
- **服务端 BUSY 门禁**：`agentd` 人格支持 `activityJson`，`POST /v1/personas/{id}/activity`，主动引擎 `BUSY` 不开口、
  `LIMITED` 降概率×0.35、过期恢复；`/v1/proactive/tick` 返回 `skipped` 原因；主动计数/活动均落盘。
- **服务端 CRUD（M5 服务端部分）**：会话创建/标已读、人格 CRUD、记忆 CRUD、预设、设置、备份导出/导入（v5，不含 secrets）、
  `/v1/push/register` 与可插拔 `PushSender`（默认 Noop）。
- **客户端服务端模式（M3/M4）**：两端设置页可配服务端地址/令牌（Android Keystore / iOS Keychain）、测试连接；
  前台按增量同步状态到本地缓存（双游标：时间戳增量 + 事件游标）、聊天走服务端 SSE（幂等 `clientMessageId`、
  离线待发队列）、主动消息经事件流落缓存并发本地通知、心情/在场由服务端下发；本地模式保留为离线形态。
- **测试**：Android **1366 条 / 126 类 / 0 失败**、`:agentcore` **90**、`:agentd` **329**、iOS **1588 条（2 skip）/ 0 失败**；两端 Debug/Release 与 lint 全过。


### 0.12 续 6（未升版本）：M5 客户端写路径、媒体上传、取消 iOS 截断、测试补全

- **M5 客户端**：服务端模式下人格/记忆/预设/设置/备份的写路径全部走 `/v1/*`，服务端成功后才更新本地缓存；
  失败明确提示、不静默回退本地写；本地模式（离线）行为不变。
- **媒体**：`agentd` 新增 `POST /v1/media`（原始字节、类型/大小校验、sha256 去重）、`GET /v1/media/{id}`（ETag/304）、
  `DELETE`（被引用 409）；备份 v5 带媒体（8 MiB/张、128 MiB 总量，超限跳过计数）。两端在服务端模式下
  **先压缩（头像 512/400KB、聊天图 1600/800KB）再上传**，渲染走带鉴权的媒体缓存；本地模式仍存本地文件。
- **取消 iOS 截断**：世界/话风/人设字段的解析、存储、`sourceHash` 一律保留全文（仅渲染提示词时按上限裁剪），
  与 Android 一致；Android 同步去掉解析阶段截断。共享 `hash-cases.json` 与长文本往返测试覆盖。
- **M6 推送**：按约定保留为「服务端部署者自行配置」——服务端提供 token 注册与可插拔 `PushSender`（默认 Noop），
  APNs/FCM 协议、证书与发送实现不进入仓库；无推送时客户端前台事件同步兜底。
- **测试补全**：Android instrumented 在模拟器跑通 56 条（含 v13→v14 迁移、UI 行为、媒体压缩）；
  iOS App-hosted 16 条（10 条 Kotlin 等价性 + 6 条 UI 行为）通过 `simctl spawn xctest` 跑通。
- **测试**：Android 1412 / 132 类 / 0 失败 + 56 instrumented、`:agentcore` 90、`:agentd` 358、iOS 1625（2 skip）+ 16 App-hosted；
  两端 Debug/Release 与 lint 全过。

### 0.12 续 7（未升版本）：新装 / 重装后夜里「只标已读、不回」的修复

- **根因**：种子人格（Android `SeedPersonas.build`、iOS `SeedPersonas.build`）没有显式写睡眠字段，
  于是取了实体的默认 `sleepEnabled = true`；配合 `SleepPolicy` 的默认窗口 **23:30–08:00** 与唤醒阈值 **6**，
  用户夜里发 1–5 条只会被标成「已读」、一句都不回（`GenerationEvent.Silent`）。
  项目改名为 Yap（applicationId / bundleId 变更）等于全新安装，旧的自定义设置全部丢失，这个问题才暴露成
  「发消息没反应」。模拟器实测该行是 `Elias|1|||6`（`sleepEnabled=1`、两个时段 NULL、阈值 6）。
- **默认不睡**：种子人格（两端）显式 `sleepEnabled = false`；新建人格的表单默认也改成 `false`
  （Android `PersonaForm`、iOS `PersonaEditorScreen`）。实体 / 备份模型的数据类默认值**保持 `true`**，
  不动既有解码与迁移路径 —— 睡眠是可选功能，用户在人格编辑里打开才算数。
- **一次性迁移**：对「从没配置过睡眠」的既有数据自动关掉睡眠。条件是
  `sleepEnabled = 1`（iOS 老库该列为 NULL 时按 1 处理）`AND quietSleepMinutes IS NULL AND quietWakeMinutes IS NULL AND version = 1`；
  用户自己填过时段、或改过人格（`version > 1`）的人一律不动，也不改 `updatedAt`（不打乱首页排序）。
  Android 在 `PersonaRepositoryInitializer.seedIfNeeded()` 启动时执行（`PersonaDao.disableSleepFor`），
  iOS 在 `AppEnvironment` 启动时执行（`SleepDefaultsMigration.runIfNeeded`）；两端都用一次性标记
  `sleep_defaults_migrated_v1`（DataStore / UserDefaults）保证只跑一次，并写一条 debug 日志（只有人数，不含用户内容）。
- **唤醒阈值默认 6 → 3**：Android `PersonaEntity.wakeThreshold` 与 iOS `PersonaFieldLimits.wakeThresholdDefault`。
  只影响新建/种子人格；迁移不强行改既有值，列上的 `DEFAULT 6` 保持不变（改它要一次数据库迁移）。
- **可读性**：当一轮因睡眠被静默时，聊天页在**输入框上方**显示一行提示
  「他睡着了（08:00 醒）；连着发几条可以叫醒他」/「He's asleep (up around 08:00). A few messages in a row will wake him.」。
  它不是消息气泡、不写库，他醒着（`defer_reply`）、开着免打扰（叫不醒）时不显示；收到回复或睡醒后自动消失。
  顶栏原有的 presence 状态行（「睡着了 · 08:00 醒」）保留，两处说的是不同的事。
- **测试**：Android 新增 `SeedPersonasTest`、`PersonaEntityDefaultsTest`、`PersonaFormDefaultsTest`、
  `SleepDefaultsMigrationTest`（一次性 / 条件 / 用户自设时段 / `version > 1`）、`ChatSleepHintTest` 与接线契约测试；
  iOS 新增 `SeedPersonasTests`、`SleepDefaultsMigrationTests`（真库：条件、只跑一次、NULL 老行、`updatedAt` 不变）与
  `SleepHintTests`。全量 Android `:app:testDebugUnitTest` **1444 条 / 140 类 / 0 失败** 与 iOS `swift test` **1647 条（2 skip）/ 0 失败** 均通过；`:app:assembleDebug` 与 `:app:lintDebug`（0 error / 67 warning，均不在本次改动文件里）通过。
- **同一类「新建」入口也一起改了**：从预设「复制到人格」（Android `PersonaDraft` 的默认值、iOS
  `PersonaPresetApplier.newPersona`）与老格式人格 JSON 导入（`PersonaRepository.importPersonas`）
  都不经过编辑页表单，过去会继承实体默认的 `true` —— 复制/导入出来的新人格同样会夜里只标已读，现在都按「不睡」处理。
- **已知限制**：从**备份**恢复的人格走备份模型的默认值（`true`），没有跟着改 —— 那是历史语义（备份要能还原当时的他），
  用户需要自己看一次睡眠开关。

## 0.11.2

小改动：**把「给 AI 代理用的操作说明」与「给开发者看的项目文档」分开**，并清理文档里混进来的工作区痕迹。
版本 `0.11.2`，两端构建号 **17**；schema 仍 v10、备份仍 v2；**没有代码行为变化**。

### 文档结构

- **面向 AI 编码代理与自动化的操作说明移出仓库**：哪些目录是产物、版本号要填在哪里、工作区构建命令、
  并行分工约定、数据/契约改动清单、审计流程、完成标准，这些属于维护者的本地工作区（`.agents/`，不进仓库）。
  仓库里只保留面向人的文档；面向维护者的版本策略与发版清单留在 `CONTRIBUTING` 的「版本号与发版」一节。
- 删除 `docs/VERSIONING.md` / `.en.md`：其中的维护者内容并入 `CONTRIBUTING`，其余属于本地工作区说明。
- 新增 **`docs/README.md`**：面向人的文档总索引（项目文档 / 跨端契约 / 子项目文档 / 贡献流程约定）。
- `docs/ROADMAP-1.x.md` 移到 **`docs/design/`** 并加「历史设计记录」标注：它们是当时那一版的行为规格，
  保留供追溯设计取舍，不再被当成当前行为说明。
- `docs/audits/README.md` 重写为面向读者的「审计记录」：怎么读严重度、每份报告修在哪个版本、以及
  **已接受的取舍**；「怎么发起一次审计」这类流程性内容不再写进仓库。

### 文档清洁

- 公开文档里不再出现工作区路径与只在本机可用的命令（`.tooling/` 下的 Gradle、本机 SDK/JDK 路径、
  本机模拟器名）。`agentd/README.md` 与 `spec/agent-core/README.md` 的构建命令统一为 `./gradlew`。
- `CONTRIBUTING` 里「不要提交」清单改写为面向贡献者的说法（本地工具链/缓存目录），不再指向某个具体工作区目录。
- 新增 `.github/`：PR 模板（平台勾选、双端测试、数据结构/备份/文案改动清单）与 Bug / Feature issue 模板
  （含「是否符合项目设计立场」一栏，避免反复讨论已刻意移除的旋钮）。
- `.gitignore` 增加 agent workspace 约定段（`/.agents/`、`/.scratch/`、`/workspace/`、`*.agent-notes.md`），
  并保留原有对 `.tooling/`、`dist/`、`docs/screenshots/`、构建缓存的忽略。

### 版本与发布

- `versionCode` / `CURRENT_PROJECT_VERSION` 同步为 **17**，`versionName` / `MARKETING_VERSION` 为 `0.11.2`。
- 本版打 tag `v0.11.2` 并在 GitHub Release 附本更新日志（`prerelease`）。

### 测试

无代码改动，测试数量与 0.11.1 相同：Android 970（91 类）、`:agentcore` 47、`:agentd` 34、iOS 1187；
两端的 Debug/Release 构建与 `lintDebug` 在本版提交前重新跑过。

---

## 0.11.1

小改动：**针对 0.11.0 新代码（`:agentcore` 抽取、两端 agent 客户端、P2 改动）的三份独立审计**，
0 个 P0、**10 个 P1**、51 个 P2；P1 全部修复，P2 修了绝大多数。版本 `0.11.1`，两端构建号 **16**，
schema 仍 **v10**、备份仍 **v2**。（本版**打 tag 并在 GitHub Release 里发布本更新日志**。）

### 安全与稳健性（服务端 / 共享策略）

- **`agentd` 的 HTTP 层重写**（仍零第三方依赖）：JDK `HttpServer` 单线程 + 无读超时会被**一条半开连接**打瘫
  （实测 `/health` 3s 超时）。改为固定 4 worker + 有界队列 + 每连接 **5 秒总读时限**（超时 408）、
  **1 MiB 请求体上限**（超限 413）、拒绝 chunked（411），并支持可选 `--token`（`Authorization: Bearer`，缺失 401）。
  实测：半开连接下 `/health` 从 3.01s 变 **0.8ms**；8 MiB body 从「200 + 回显 8MB」变 **413（响应 227 B）**。
- **错误响应不再回显输入**：未知工具名/路径/异常细节截断或不回显，500 只返回「服务内部错误（错误编号）」，
  细节只进 stderr；`/health` 保留服务名与版本。
- **`WebUrlPolicy` 加固**（`web_fetch` 的地址校验）：堵住 `http://127.0.0.1:8080\@example.com/`、
  `localhost.`、`2130706433`、`0x7f000001`、`0177.0.0.1`、`[::ffff:7f00:1]`、`127.0.0.1.nip.io`、
  百分号编码 host、userinfo 等绕过；IPv6 内网段（ULA/link-local/NAT64/6to4）一并覆盖。
- **JSON 解析加限额**：嵌套深度 64、单字符串 256 KiB、数字字面量 128 字符、单容器 16384 项，重复键直接拒绝。
- **iOS 媒体守卫**：`MediaStorage.delete` 与 `AttachmentLocation.resolve` 之前没有目录检查（实测能删/指到 App
  容器外）；现在只允许 `attachments/` 内、拒绝 `..`/百分号穿越/符号链接逃逸。
- **iOS `validatePageUrl`** 补齐内网字面量与变形的同等校验（与 Android 策略对齐，但实现独立）。

### 功能正确性（P1）

- **备份引用完整性**（Android）：`messages`/`memories` 非空但 `sessions`/`personas` 缺失的备份以前能过闸，
  `REPLACE` 清空本机后这些行落不下去 → 本机没了、备份也没接回。现在**在清空之前**做跨表引用校验，
  不通过整份拒绝，预览也会先提示。
- **定位窗口不感知新消息**（Android）：从检索跳进有界窗口后，自己发的消息/新回复不可见且没有入口。
  现在发送/重新生成/编辑会回到最新一屏，后台写入会让「回到最新」入口重新出现。
- **事务内删媒体文件**（Android）：备份替换/删人格在 DB 事务里删文件，回滚后数据还在、文件已永久删除。
  现在只收集路径，**提交成功后**才删。
- **备份察觉外部删除**（iOS）：聊天页里清空数据后返回仍显示已删消息、轮询永远无效重试；现在用轻查询判定并
  重置窗口/回首页。
- **SSE 8MB 上限在真实链路生效**（iOS）：上限原来只在解析器里，客户端自己攒字节导致缓冲无上限；现在在累加处中止。
- **连接超时生效 + 熔断**（iOS）：`connect` 3s 被请求级 8s 覆盖，agentd 不可达时每次决策等满 8s；现在
  3s 生效、单次失败后 30 秒内走本机。
- **导出/导入尺寸对称**（iOS）：导出没有 64MB 上限而导入有，可能导出自己导不回来的备份；现已对齐。
- **备份解析移出主线程**（两端）：Android 的 JSON 解析/映射/编码、iOS 的读文件 + 预解析都可能在主线程
  ANR；现在全部在 IO/detached 上执行。

### 其余修复（P2 摘要）

- Android：`hasKey` 三态接到 UI（能解密才算已配置，解不出的密钥给出明确状态）；通知深链校验会话存在且属于
  当前人格（`MainActivity` 不再直接信任 extra，模拟器实测伪造 id 不再导航）；`preset.avatarUri` 也只写文件名；
  清空聊天会撤通知并结束 openSession；备份替换 bump 会话世代；`WIPE_ALL` 补 `api_usage` 且表清单双向守卫；
  远程客户端禁重定向、响应上限 256KB、回退可观测；API 26–27 的 1024 长边真正落实；复制人格不再共享头像文件；
  选图落盘与草稿登记进不可取消区域；release 构建强制进程内决策。
- iOS：严格远程模式失败不再被 `try?` 吞掉（有诊断计数与可见错误）；通知深链温启动消费后即清并校验会话；
  深度回忆改窗口查询；`marksRead` 缺省按 DND 推导；`asleep` 与 Kotlin 契约对齐；`AsyncMutex` 响应取消；
  远程响应 1MB 上限；首页轮询受控（不可见即停）；预览更新改定向 UPDATE；焦点窗口 240 条上限；
  四个端点的 golden 测试补齐。
- i18n 守卫加强（两端）：`values-en`/`en` 含中文标点或全角字符也判红、无位置参数格式符互换判红、
  注释里出现的资源键不再算「已引用」。

### 测试

| 目标 | 结果 |
| --- | --- |
| Android | **970 条 / 91 个测试类 / 0 失败** |
| `:agentcore` | **47 条 / 0 失败**（94 条契约用例全覆盖） |
| `:agentd` | **34 条 / 0 失败** |
| iOS | **1187 条 / 0 失败**（Debug 与 Release 均零 Swift 警告） |
| 其它 | `lintDebug` 通过（顺手修了一条格式串 lint error）、`assembleRelease`、`:agentd:fatJar` 成功 |

### 审计报告

`docs/audits/0.11.1-{android,ios,security-contract}.md`（本轮发现逐条可查）。

---

## 0.11.0

大改动：**agent 决策层收敛成一份实现（`:agentcore`），两端客户端都走客户端/服务端分层**；
同时把三份审计里剩下的 P2 项清完。版本 `0.11.0`，两端构建号 **15**，schema 仍 **v10**、备份仍 **v2**。
（该版本未创建发布 tag。）

### 架构：一份决策实现 + 客户端/服务端分层

- 新增 Gradle 模块 **`:agentcore`**（纯 Kotlin/JVM，零第三方依赖）：把原先散在 `app/.../domain/` 的
  **40 个纯决策文件**（睡眠判定、`defer_reply`、主动上限、心情、心跳、记忆生命周期、向量、检索、备份文案、
  擦除清单、错误模型…）移进来，**包名不变**。`:app` 与 `:agentd` 都依赖它。
- `:agentd` 不再用「`kotlin.srcDir(app/domain)` + 14 条 exclude」的临时手段，**排除列表已删除**；
  JSON 解析器也收进 `:agentcore`（`com.yap.app.core.json`），仓库里只有一份。
- 新增 `AgentCore` 接口（4 类决策）与 `InProcessAgentCore`（只做整形、全部委托既有纯函数）、
  `AgentCoreProtocol`（路径与字段名常量，`agentd` 路由表与两端客户端共用）、`AgentCoreCodec`（HTTP 编解码）。
- **Android 客户端**：`AgentCoreClient`（`InProcess` 默认 / `Remote` 走 OkHttp 调 `agentd`），
  由构建开关 `-PagentCoreRemote=true`（`BuildConfig.AGENT_CORE_REMOTE`）选择；远程失败**自动回退进程内**并记日志，
  开关关闭时**不构造远程客户端、零网络请求**。接线：睡眠判定、`defer_reply`、工具参数夹取、每日主动上限（3 处）。
- **iOS 客户端**：Kit 里的 `AgentCoreClient` 协议 + `Native` / `Remote`（URLSession）/ `Fallback` 三种实现，
  由 `YAP_AGENT_CORE`（`native|remote|remote-strict`）或 Info.plist 选择，**默认 native（零请求）**；
  接线同一组 4 处。模拟器实测 `remote-strict` 下 4 个端点全部 HTTP 200。
- **没有新增任何用户可见设置项**；决策层仍不持有状态（会话/记忆/用量都在客户端数据库）。

### Android：P2 收尾

- 聊天图片「选了没发出去」、头像「选完取消/连选两张」留下的文件 → 按草稿生命周期回收（含目录守卫）。
- `createSession` 改成幂等 upsert（撞唯一索引就回读），首页点联系人不会失败。
- `createdAt` 撞同一毫秒 → `(createdAt, id)` 稳定序；`deleteAfter` 改复合游标，编辑消息不再漏删旧回复。
- 「已读」判定从每条消息扫窗口（O(n²)）改成一次算好最后一条助手时间（O(1)）。
- 停止生成只剩工具调用时不再落空洞气泡（三分：不落库 / 状态消息 / 正常）。
- 多模态上下文加**数量 + 尺寸双限**（最近 2 条、每条 ≤4 张、合计 ≤8 张；单张长边 ≤1024、≤320KB、整体 ≤4MB），发送前重编码。
- 检索跳转改成**一次查询算窗口**、窗口封顶 240 条（不再「目标→最新」全量爬），并给「回到最新」入口。
- `hasKey` 改成「能解出非空 Key 才算已配置」，解不出时报明确错误而不是裸奔 401。
- `web_fetch` 加 scheme/host 校验（拒绝内网字面量与 `file:`/`javascript:`/`data:`）。
- 备份导入加 **64MB 上限**（按字节流读到上限即中止）；导出**不再写设备绝对路径**（头像只留文件名）；
  空备份不再导出空文件。
- 主动页时钟格式跟随语言（英文 12 小时制）；通知支持按人格/全部取消；分页滑窗时「上面还有更早」提示会重算。

### iOS：P2 收尾

- 长会话四处全量读历史 → 窗口查询 + 「最后一条用户/助手消息」精确查询（400 条会话只读 200 行）。
- 聊天页轮询从「一律 4 秒」改成策略：后台/不可见全停、流式中停消息轮询、发呆 20 秒。
- 分页窗口不再被新消息挤掉已加载历史（按 id 追加、上限 600 条）。
- 首页未读/预览用轻量摘要轮询（可见+前台 4 秒，变了才重画）。
- 通知冷启动深链改为持久化待打开会话（`PendingDeepLink`），端到端实测通过。
- 内置浏览器加固：`nonPersistent` 数据仓、**默认关闭 JS**、只放行 http/https，危险 scheme cancel、`mailto/tel` 交系统。
- 「删除全部数据」后按当前语言**重新播种**人格（不会再得到一个空白 App）。
- 相册选图优先走文件 URL + 按目标尺寸解码（不把原图整张读进内存），超大文件在解码前拒绝。
- 备份导入加 64MB 上限；导出剥离设备绝对路径；SSE 单行上限对齐 8MB；`isConfigured` 改为真正读 Keychain。

### 测试

| 目标 | 结果 |
| --- | --- |
| Android | **909 条 / 87 个测试类 / 0 失败** |
| `:agentcore` | **14 条 / 0 失败** |
| `:agentd` | **20 条 / 0 失败** |
| iOS | **1098 条 / 0 失败**（Debug 与 Release 构建均零 Swift 警告） |
| 构建 | `:app:assembleDebug`、`:app:assembleRelease`、`:agentcore:build`、`:agentd:build`、`:agentd:fatJar`、iOS Debug/Release 全部成功 |

---

## 0.10.2

小改动：**聊天记录检索**（两端，iOS 此前完全没有）、**三份独立审计后的缺陷修复**（1 个 P0、17 个 P1）、I18N 收尾。
版本 `0.10.2`，两端构建号 **14**，schema 仍 **v10**、备份仍 **v2**。

### 聊天记录检索

- 新增跨端契约 `spec/search/`（16 条用例：摘要/高亮规则 8 条 + 排序/过滤 8 条），两端各有一个测试读同一批文件。
- **检索语义**：`LIKE` 参数化粗筛（用户输入的 `%`/`_`/`\` 全部转义，按普通字符处理）+ `ChineseTerms` 词元召回，
  再在内存里精排（命中词元数 → `relevance` → 时间 → id），候选上限 500 条；命中超出时界面明确提示「只显示最近 N 条」。
- **Android**：结果分「消息 / 会话」两段，支持按人格与时间（7 天 / 30 天）过滤，摘要高亮、每页 30 条、滚动加载，
  点结果**跳回会话并定位到那条消息**（复用分页游标，2 秒高亮淡出），返回保留查询与滚动位置。
- **iOS**：此前没有任何聊天搜索；本次新建搜索页，语义与 Android 完全一致，同样支持过滤、分页与跳转定位高亮。
- 取舍：不建倒排索引表（那会牵动 schema v11 与所有写路径），检索是低频操作，几万条消息的 `LIKE` 粗筛是毫秒级。

### 审计与修复

三份独立审计（`docs/audits/`）共 46 条发现：**1 个 P0、17 个 P1**，本轮全部修复。

**iOS（P0/P1）**

| 条目 | 问题 | 修复 |
| --- | --- | --- |
| P0-1 | SQLite 事务深度是裸 `Int`，被 MainActor / 生成任务 / 心跳 / 并发后台任务共用 → 双重 `BEGIN` 或写被并入他人事务后回滚丢失 | 事务整段串行化（递归锁），单条写也纳入同一把锁；嵌套退化为外层事务的语义保留 |
| P1-1 | 抽记忆 / 压缩任务整行写回会话，覆盖期间的新消息、未读与摘要 | 改为定向 `UPDATE` + 乐观检查（计数按旧值、摘要按单调位置），会话被删不复活 |
| P1-2 | 冷启动与回前台各补算一次 → 双心跳、双主动消息、双通知 | `CatchUpGuard`（in-flight + 30 秒间隔）+ `beat` 单拍互斥 |
| P1-3 | 文档写着「前台每 30 秒一拍」，实际没有实现 | `ForegroundPulse` + 前台定时器，进后台停；低电量放宽到 2 分钟 |
| P1-4 | BGTask 把「无事可做」上报为失败，到期只 cancel 不 `setTaskCompleted` | 状态机保证只上报一次、无事可做算成功、到期上报失败 |
| P1-5 | `ResilientKeychain` 在真机也会把 Key 写进 UserDefaults（明文） | 兜底收紧到 `DEBUG && simulator`；真机写失败直接抛错 |
| P1-6 | 发布包 `NSAllowsArbitraryLoads=true` | 发布 plist 只留 `NSAllowsLocalNetworking`，Debug 用独立 plist；Release 里打开「允许不安全 HTTP」会明确报错 |
| P1-7 | 备份页「删除全部」不删图片与头像 | 媒体清理收进 `DataReset`，两个入口共用 |
| P1-8 | `MediaStorage.deleteAll()` 重建目录后丢 `isExcludedFromBackup` | 重建后重新设置并加测试 |
| P1-9 | 合并导入整行覆盖本机会话并把 `attachmentsJson` 置 nil（同机合并自身备份会丢全部图片引用） | 逐字段合并，本机附件引用与较新的字段一律保留 |
| P1-10 | 发送失败吞掉草稿与已选图片、留孤儿文件 | 只在真正开始发送时清空，失败保留草稿与图片并提供重试 |
| P1-11 | 门控等待对取消不敏感（点了停止仍可能等 20/60 秒后真的发请求） | `withTaskCancellationHandler`：取消即摘除等待、不占名额；后台道用后台文案 |
| 安全 P1-0 | 备份导入能改既有配置的 `baseUrl` / 打开明文，同时保留本机 Key | 地址与明文开关**一律保留本机值**并写导入告警；新配置明文强制关 |
| 安全 P1-5 | 备份多写 6 个 sync 字段（含 `serverSessionId`） | 从备份模型与编解码中删除，与 Android 逐字一致 |
| 安全 P1-6 | 删人格不级联清回声/思考留痕，默认人格不转移 | `PersonaDeletion` 统一级联 |

**Android（P1）**

| 条目 | 问题 | 修复 |
| --- | --- | --- |
| P1-1 | 旧 ViewModel 的 `onCleared` 把「当前打开会话」清成 null → 正在读的会话被判未开：未读 +1 并弹通知 | `OpenSessionTracker` 比较后再清；通知判定抽成 `ChatNotificationPolicy` |
| P1-2 | 清空上下文后，在跑的历史压缩把旧摘要写回 | 会话世代号 + 写入前校验（世代、存在性、摘要边界） |
| P1-3 | 「清空聊天」「删除全部」「删人格」「备份替换」漏清 `memory_versions` / `memory_echoes` / `memory_vectors` / `memory_consolidations` / `agent_ticks` / `agent_intents` / 聊天图片 | 唯一清理清单 `ErasurePlan` + `DataEraser`，四条路径全部走它，并给 `MemoryReconsolidator` 补删除入口 |
| P1-4 | 导出聊天在主线程做 DB 循环 + JSON 编码 | 下沉 IO、分批流式写盘、进行中与失败状态 |
| P1-5 | `send()` 被 `memoryJob.join()` 阻塞最长一次完整模型往返 | 抽记忆完全后台化，发送不等它 |
| P1-6 | 删除会话与在跑的生成竞态，收尾会写进已删会话 | 按会话取消生成 + 收尾前校验世代与存在性 |
| 安全 P1-0 | 同 iOS：备份导入能改配置地址/开明文 | 同上（共用校验与告警文案） |
| 安全 P1-2 | 主 manifest `usesCleartextTraffic=true` | 主 manifest 关闭，仅 `src/debug` 打开；Release 下 http 报明确错误 |
| 安全 P2-1 | 自定义 Headers 未拦 `Host` / `Content-Length` 等 | 保存与请求组装两处拦截并报错 |
| 安全 P2-2 | 删除附件没有「只删自己目录」守卫 | `MediaOwnership` 校验（防 `..`、同前缀目录） |
| P2-3 | 门控取消时补偿名额可能在已取消协程里拿锁 → 名额泄漏 | 补偿放进 `NonCancellable` 临界区 |
| P2-4 | `AgentLoop.tick` 无互斥（心跳 / WorkManager / 规则三入口） | `AgentTickLock` 非重入 |
| P2-5 | 会话正开着时，规则型主动消息仍弹通知 | 写入 + 标已读，不打扰 |

### I18N 收尾

- 工具痕迹标题（会显示在气泡下的「联网搜索」「记住了」等）走资源，模型面向文本保持中文不变。
- 剩余「数据层会上屏的中文」清完（会话标题兜底、消息预览、默认规则说明、抽取结果句等）。
- 英文单复数补到 0/1/2 三种值；英文长文案在首页、设置、搜索、人格编辑页做换行与缩短。
- 守卫加强：`values-en` / `en` 资源**不得含 CJK**、格式符按位置逐一比对、资源必须被引用（死键报错）。

### 测试

| 目标 | 结果 |
| --- | --- |
| Android | **823 条 / 73 个测试类 / 0 失败** + 3 条 instrumented（真机资源下中英标题切换） |
| iOS | **1016 条 / 0 失败**（Debug 与 Release 构建均零 Swift 警告） |
| agentd | 19 条 / 0 失败 |
| 构建 | `:app:assembleDebug` / `:app:assembleRelease` / `:agentd:build`、iOS Debug 与 Release `xcodebuild` 全部成功 |

---

## 0.10.1

小改动：把 0.10.0 并行开发里各条线自己承认的「没做完」补齐，并修掉并行带来的口径不一致。
版本 `0.10.1`，两端构建号 **13**，schema 仍 **v10**、备份仍 **v2**。

### Android：错误文案不再是硬编码中文

- 新增 `domain/UserError.kt`：`UserError`（`Domain(code, arg, number)` / `Network(kind)` / `Unknown`）、
  **74 个** `DomainErrorCode`、`NetworkErrorKind`（含 `Http(status)`、`ResponseTooLong(limit)` 等）。
- 数据层与网络层只抛结构化错误；UI 通过 `ui/common/ErrorText.kt`、`BackupText.kt`、`RevisionReasonText.kt`
  映射到资源。新增 **216 个 key × 中英两份**。
- **落库的错误也本地化**：`messages.errorMessage`、`ToolTraceItem.detail`、`proactive_rules.lastError`
  改存 ASCII 令牌 `@mf-err:...` / `@rev:...`，渲染时按语言翻译；老库里的中文值原样显示（不改写、不加迁移）。
- `ChineseLiteralGuardTest` 扫描范围扩到 `data/` + `network/`：一次性扫出 378 处中文，上屏的已全部结构化，
  剩下 18 个面向模型/落库的提示词文件进白名单并逐条写明理由。
- 备份摘要、导入 warnings、人格版本 reason、连接测试、HTTP 状态码、联网工具错误、聊天错误气泡全部走资源。

### 两端：聊天页分页

- 首屏只读最近 **60** 条，滚到顶部再取 60 条（`createdAt` + `id` 复合游标），直到最早；顶部有「加载更早」。
- **未读计数、已读回执、DND 与唤醒阈值仍走全表查询**，不受窗口影响；搜索、导出、历史压缩、备份仍是全量路径。
- 两端各加契约测试钉住这些性质（Android `ChatPagingContractTest` / `MessagePagingTest`，
  iOS `ChatPagingTests`），防止以后被顺手窗口化。
- 已知代价：把整个历史翻完之后，可观察区间等价于全量——分页降低的是「进会话与日常浏览」的峰值。

### iOS：补上 0.10.0 报告里剩下的三条真缺口

| 缺口 | 现在 |
| --- | --- |
| `web_search` 之后的重要性打分 | 移植 Android `WebSearchScorer`（同一套判定标准、阈值 3、温度 0.2、用量类目 `SCORING`、失败降级不记），写入前多做一次相似度去重（这一点比 Android 严） |
| 记忆回声清理 | `promote` 成功后按人格清理 `memory_echoes`（对齐 Android `deleteByPersona`），`reinforce` 不动 |
| 主动唤醒「现在试一次」+ 失败原因 | 列表每行可触发一次（成功落消息 + 通知并清 `lastError`，失败写 `lastError` 并显示；不改变排期；睡着时拒绝） |

- 顺带：`ProactiveLevel` 编辑页文案改为调用 domain 的实现（不再各写一份）。
- 用量口径：自主思考/心跳开口记 `AGENT`，「现在试一次」记 `CHAT`（与 Android `ProactiveRepository.triggerNow` 一致）。

### 两端：媒体与失效路径

- 换头像/恢复默认/删人格时删除对应的头像文件；「清空全部数据」删头像目录。
- 备份导入时 `avatarUri` 指向不存在的文件 → 清空字段并计入导入 warnings（中英文案）。
- iOS：编辑器里选了新头像又取消时删除孤儿文件；Android：删除消息/清空上下文/删会话时删除附件文件。

### 其它修复

- Android 后台路径的窗口查询换成精确查询（`lastUserMessage` / `lastUserMessageAt` / `recentUserMessages`），
  不再出现「最近 40 条里没有用户消息就取不到上次说话」的边界。
- Android SSE 单行加 **8MB** 上限（超限按「回复太长」中止，不把一行吃进内存）。
- iOS 编译警告 **9 → 0**：`LlmConcurrencyGate` 把加解锁收进同步临界区；`AgentLoop` /
  `MemoryConsolidator` / `MemoryExtraction` 改用 `AsyncMutex`（等待者挂起而不是占住线程），门控语义与超时逐条保留。
- iOS 英文单复数补齐（备份/清空摘要、规则、时间等，`1 personas` 这类边界修正），
  `Recall` 的工具详情前缀改为本地化的显示版本（提示词版本仍是中文）。
- `Recall` / 工具痕迹标题里仍会露出的中文（如「联网搜索」「记住了」）本轮未处理，见「已知限制」。

### 测试

| 目标 | 结果 |
| --- | --- |
| Android | **683 条 / 0 失败**（基线 639 + 44） |
| iOS | **872 条 / 0 失败**（基线 827 + 45） |
| agentd | **19 条 / 0 失败** |
| 构建 | `:app:assembleDebug`、`xcodebuild build`、`:agentd:build` 均成功；iOS clean build 零 Swift 警告 |

---

## 0.10.0

版本号 `0.10.0`（大改动：新增国际化能力与跨端 agent 契约），构建号 `versionCode` / `CURRENT_PROJECT_VERSION` 同步为 **12**，数据库 schema 仍是 **v10**，备份格式仍是 **v2**。

### 国际化：英文 UI

- Android：新增 `app/src/main/res/values-en/strings.xml`（与 `values/strings.xml` 键集合一致），
  通过 `android:localeConfig` 声明 `zh` / `en`，语言跟随系统，没有应用内切换；
  基资源 `values/` 为中文，未匹配语言时回落中文。
- iOS：新增 `ios/YetAnotherPal/Resources/zh-Hans.lproj/Localizable.strings` 与
  `en.lproj/Localizable.strings`，`CFBundleDevelopmentRegion = zh-Hans`、
  `CFBundleLocalizations = [zh-Hans, en]`，两份 `InfoPlist.strings` 本地化本地网络权限说明，
  由 `Bundle.main` 自动选择；同样没有应用内切换。
- 模型语言：两端在 system 提示词末尾追加语言指令小节，要求模型使用与用户相同的语言回答。
  两端都按界面语言提供中 / 英两套模板（Android `values{,-en}` 的 `prompt_language_directive`，
  iOS `Localizable.strings` 的同名 key），模板文案逐字一致，只有占位符写法不同（`%1$s` / `%1$@`）。
  除该小节外，提示词脚手架、日志与代码注释不翻译。
- 资源一致性守卫：Android `StringResourceParityTest`（键集合与占位符）、`ChineseLiteralGuardTest`（UI 层不得硬编码中文）、
  `LanguageDirectiveTest`（提示词末尾追加语言指令）；iOS `LocalizationResourceTests`、`UIChineseLiteralGuardTests`。
- 文案条数：Android `values` / `values-en` 各 **876 条**（856 `<string>` + 20 `<plurals>`），另有 `seed.xml` 各 12 条、
  按语言取值的 `usage_tokens_compact_divisor` 与日期/时间格式资源；iOS 两份 `Localizable.strings` 各 **765 条**
  （App 层约 637 处调用点 + Kit 层 233 个 key），`InfoPlist.strings` 各 1 条。
- 种子人设随语言：中文系统「林深 / 阿岚 / 墨」，英文系统 Android「Elias / Finn / Ansel」、iOS「Rowan / Juno / Ellis」；
  仅在首次安装时生效，不迁移、不改名已有人格。

### Agent 核心契约

- 新增 `spec/agent-core/cases/*.json`：**94 条**与语言无关的用例（睡眠判定 16、`defer_reply` 9、主动上限 14、
  心跳 17、记忆衰减 22、工具参数夹取 16），每条含 `name` / `input` / `expect` / 可选 `why`；浮点断言带 `tolerance`。
- Android 测试 `app/src/test/java/com/yap/app/spec/AgentCoreSpecTest.kt` 与
  iOS 测试 `ios/YapKit/Tests/YapKitTests/Spec/AgentCoreSpecTests.swift` 读取**同一批** JSON，
  分别喂给两端已有的领域实现；任何一端单独改行为，另一端立刻红。

### 内嵌服务骨架：agentd

- 新增独立 Gradle JVM 模块 `agentd/`（Kotlin/JVM，仅用 JDK 自带 `com.sun.net.httpserver`，零第三方依赖），
  直接编译 Android 的纯领域源码（同一批源文件，不复制）。
- 对外提供 `GET /health` 与四个核心接口（`sleep-decision` / `defer-plan` / `proactive-cap` / `tool-args`），
  可独立运行，也可作为本机端口的嵌入式服务；不接触数据库与 LLM 调用，无鉴权、无状态持久化。
- 实测：`:agentd:test` **19 条**全绿（18 条 handler + 1 条真端口集成）；`agentd/build/install/agentd/bin/agentd --port 8788`
  与 `java -jar agentd-all.jar` 都能起，`/health` 与四个接口按约定返回（错误统一 `{"error":{"message","code"}}` + 4xx/5xx）。
- **本轮未接线**：Android 客户端没有启动 agentd，iOS 侧也没有调用它的代码；两端仍走原有进程内实现。

### 契约测试暴露并修掉的两端不一致

契约用例写完之后，两端有 10 处实现细节并不一致（数值、边界、脏数据降级各有各的做法）。处理原则：以
`docs/design/roadmap-1.9.md` 的规格为准，两边都合理时以 Android 为准并对齐 Swift。主要几条：

| 项目 | 修复前 | 修复后 |
| --- | --- | --- |
| 免打扰时是否写已读 | Android 写、iOS 不写 | 统一**不写**（规格 §六「不显示已读后的任何反应」），`SleepPolicy.Decision.marksRead` 决定 |
| 被叫醒后的心情强度 | iOS 强制 1、Android 减 1 | 统一「强度 −1」 |
| 睡醒同一分钟的脏数据 | Android 回退默认 23:30–08:00、iOS 视为永不睡 | 统一回退默认 |
| `memoryRetention` 是否影响衰减 | Android 完全没接、iOS 有系数 | 统一接上（×1.6 / 1.3 / 1.0 / 0.75 / 0.5） |
| `proactiveLevel` 是否影响心跳 | Android 乘 0.7–1.3、iOS 不乘 | 统一乘上；iOS 新增 `ProactiveLevel` |
| `defer_reply` 夹取 | iOS 在三处各写一份 | 两端都收进纯函数 `DeferReply` / `ToolArgClamp`，契约测试直接覆盖 |
| 脏心情数据的归一化 | Android 会写出越界 label / 强度 | 两端都归一化并夹 1–5 |

### 测试

| 目标 | 命令 | 结果 |
| --- | --- | --- |
| Android | `./gradlew :app:testDebugUnitTest` | **639 条 / 47 个测试类 / 0 失败** |
| iOS | `cd ios/YapKit && swift test --disable-sandbox` | **827 条 / 0 失败** |
| agentd | `./gradlew :agentd:test` | **19 条 / 0 失败** |
| 构建 | `:app:assembleDebug`、`xcodebuild … build`、`:agentd:build` | 均成功 |
### 文档

- `README` 与 `CONTRIBUTING` 改为中英双语（`README.md` / `README.en.md`、`CONTRIBUTING.md` / `CONTRIBUTING.en.md`），
  内容改为面向贡献者的技术说明：项目状态、架构与技术栈、目录结构、构建与测试、已知限制。
- `docs/VERSIONING`、`docs/CHANGELOG` 增加英文版；新增 `docs/I18N.md` / `docs/I18N.en.md`
  与 `docs/ARCHITECTURE.md` / `docs/ARCHITECTURE.en.md`。
- 文档不再嵌入截图或链接截图文件；仓库不发布 APK 与截图。

### 版本与工程

| 项 | 值 |
| --- | --- |
| 版本 | `0.10.0` |
| 构建号 | Android `versionCode` 与 iOS `CURRENT_PROJECT_VERSION` 相等（待补：具体数值） |
| 数据库 schema | v10（本轮未变） |
| 备份格式 | v2（本轮未变） |
| Android 单测 | 619+（本轮新增数待补） |
| iOS 单测 | 810+（本轮新增数待补） |

---

## 0.9.1

内测阶段的第一次小版本更新。三件事：**你和他都能有自己的脸**、**iOS 追上 Android**、**少占一点内存**。

### 头像：他可以有，你也可以有

- **他（人格）**：编辑人格时可以从相册选一张，采样压缩后存在应用私有目录（长边 512px、JPEG 85、≤400KB），
  全部界面（首页联系人、聊天页、人格列表、设置）统一渲染；随时可以「恢复默认」退回色块 + 首字。
  文件丢了（换机、清理）不会崩，只是退回默认。
- **你（自己）**：设置里新增安静的「我的头像」一行，选一张之后会显示在**你的消息气泡旁**。
  不存在数据库里（Android 走 DataStore、iOS 走 UserDefaults），所以**没有 schema 迁移**。
- 两端一致：头像图片本身**不进备份**（备份里只留路径），换手机后重新选一张即可；导出/导入页有这句中文提醒。

### iOS 补齐落后 Android 的能力

| 能力 | 0.9.1 之前 | 现在 |
| --- | --- | --- |
| 图片消息 | 只写「（发来一张图片）」 | 相册选图 → 长边 1600px / JPEG 0.85 / ≤800KB → 气泡缩略图 → 全屏查看（缩放、翻页）→ 重启后仍渲染；文件缺失显示「图片已不在本机」 |
| 聊天侧工具执行 | 一律回「这个工具用不了」 | `think` / `web_search` / `web_fetch` / `remember` / `update_persona` / `set_quiet_hours`（含 `dnd`）/ `set_mood` / `recall` / `defer_reply`，参数夹取与回灌文案对齐 Android；失败有中文原因，不假装成功 |
| 联网工具 | 无 | `Net/WebToolsClient`（DeepSeek `/v1/tools/search`、`/v1/tools/fetch`），搜索真的联网 |
| 主动唤醒规则 | 提案只能显示，不能落库 | 落库（daily / interval、`nextTriggerAt` 按本地时区、重复只更新不新增），并且新增**「主动唤醒」页面**：列表、开关、下次大概什么时候、长按删除、手动新建 |
| 历史压缩 | 只有算法，没有编排 | 最近 24 条逐字保留、够 12 条或 24000 字压一段、一次最多 40 条、原文一条不删；带「工具协议形状 / 推脱 / 占位」拒收守卫与启动自检修复 |
| 人设卡 | 只读 | 结构化编辑（identity / traits / bond…），落库并进提示词，空卡回落到手写 `prompt` |
| 通知与后台 | 无 | 本地通知（锁屏不露正文、点开进对应聊天）+ `BGAppRefreshTask` / `BGProcessingTask` 注册 + 回到前台按错过的时间补算一次 |
| 两张遗留表 | `memory_echoes` / `agent_ticks` 未进 schema 清单、无测试 | 进入 `Schema.allTables`（15 张），补「老库缺表打开即建」的迁移测试；`remember` 的回声跨进程重启仍在 |

### 内存占用

- **Android**：Coil 改成懒初始化单例（内存缓存 `min(可用内存×12%, 48MB)`、磁盘 128MB、`respectCacheHeaders(false)`），
  头像与聊天图一律按显示尺寸解码（不再整图常驻）；六处后台路径（自主循环 / 空闲浏览 / 主动唤醒 / 记忆整理 / 记忆读写 / 人格进化）
  从「读整段历史」改成「最近 40 / 120 条 + 窗口工具」。
- **iOS**：图片按显示尺寸解码（ImageIO 缩略图）+ `NSCache`（`min(物理内存×10%, 48MB)`）+ 内存警告清空；
  只给**最近 2 条**带图消息解 data URL；附件目录标记为不进 iCloud 整机备份。
- **实测数字（不粉饰）**：
  - iOS：单张聊天图在气泡里只多约 **5.1MB**，全屏查看是唯一大头 **+21.3MB**（退出即释放，缓存受 48MB 上限约束）。
  - Android：两个规定场景（首页 30s / 聊天页 30s）PSS 变化 **<1%，属噪声量级**——不是没改，是这两个场景里 58MB 的匿名 dex 与图形缓冲占主导，
    应用自身 Java 堆只有约 11MB。真正的收益在「长历史 / 大图」路径上（后台从读几千条变 40 条、图片按显示尺寸解码），
    这两个场景没有触发它们。**已知遗留**：聊天页仍然一次性加载整段消息列表，这是长会话下最大的一块，留给下一版做分页。

### 版本与工程

- 版本号 `0.9.1`，Android `versionCode 11`、iOS `CURRENT_PROJECT_VERSION 11`（两端构建号相等，schema 仍是 v10）。
- 关于页、备份 `appVersion` 都从构建号派生，不再手写版本字符串。
- 开源准备：Apache-2.0、Gradle Wrapper、`.gitignore`（排除工具链与构建产物）、`CONTRIBUTING.md`、`CONTRIBUTING.md`。

---

## 0.9.0（旧编号 1.9.0）

**这一版只做一件事：把「设置项」从产品里拿掉，把他还成一个人。**

以前你可以调他的记性、调他回消息的快慢、给他设一个月度额度、规定他一天最多主动几次。这些旋钮让界面看起来像一台可以调的机器人，而不是一个你认识的人。这一版把这些全部删掉。

- **首页变成联系人列表**：一行一个人——头像、名字、最后一句、时间、未读。没有「最近对话」，也没有「新建对话」。
- **一人一段会话**：升级时旧的多段会话按「保留最新那一段」合并，其余连同消息一起删除。
- **他有了更具体的样子**：性格、喜欢的事、行为方式、成长经历、个人设定，加上主动程度、记性、抽记忆频率、会不会主动睡觉、睡觉时间段与被叫醒的阈值。
- **回不回由他决定**：他可以读到你的消息、显示「已读」，然后这一轮选择不回（`defer_reply`）。界面不会显示「对方正在输入」来骗你。
- **他也要睡觉**：睡着时消息留着；睡前要不要开免打扰由他自己决定；没开免打扰时，你连着叫他够多条会把他叫醒，醒来心情会缓一缓。
- **设置页瘦身**：只剩外观、聊天背景、后台常驻与通知、API 配置、用量、备份与恢复、数据清除、关于。
- **同时最多三件事，聊天永远有位**：全局并发闸 3（后台最多占 2 位）。
- **备份升到 v2**：1.8 及更早的备份仍然读得进来。

---

## 更早的版本

旧编号时代的详细设计文档仍在 `docs/` 下：[ROADMAP-1.2](design/roadmap-1.2.md)、[ROADMAP-1.3](design/roadmap-1.3.md)、[ROADMAP-1.4](design/roadmap-1.4.md)、[ROADMAP-1.5](design/roadmap-1.5.md)、[ROADMAP-1.6](design/roadmap-1.6.md)、[ROADMAP-1.7](design/roadmap-1.7.md)、[ROADMAP-1.9](design/roadmap-1.9.md)（= 0.9.0）。
