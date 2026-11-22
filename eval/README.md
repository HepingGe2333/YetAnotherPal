# Yap 生成质量评测（eval）

用**可重复的场景集 + 可解释的指标 + JSON/Markdown 报告**，量化「幻觉 / AI 味 / 像不像人」。
不是提示词许愿，也不是人工印象分：每一条回复都会被拆成 13 个分项、7 个维度，并给出失败证据。

- 只新增 `eval/**`，不改 `app/`、`ios/`、`agentd/`、`agentcore/`、`docs/`、`spec/`、`.github/`。
- 纯 Python 3 标准库，没有第三方依赖；网络只打配置的 OpenAI 兼容地址（默认 DeepSeek）。
- 不读用户真实聊天：场景里的 persona、history 全部是虚构样本。

---

## 1. 目录与用法

```
eval/
├── scenarios/              # 31 个场景，一场景一 JSON
├── fixtures/
│   └── fake_replies.json   # 离线假 provider 的固定回复
├── prompt.py               # 按 App PromptBuilder 形状拼提示词（快照）
├── tools.py                # ToolCatalog 快照（给模型看的「你能做什么」）
├── provider.py             # OpenAI 兼容 HTTP 客户端 + FakeProvider
├── metrics.py              # 13 个指标 + 7 个维度 + 汇总
├── run.py                  # CLI、报告、baseline 对比、CI 退出码
├── tests/                  # 68 条离线单测（不联网）
├── reports/                # 实测报告、基线
└── README.md
```

### 1.1 离线跑通（不需要 key）

```bash
python3 -m eval.run --provider fake
```

输出 `eval/reports/run-<时间戳>.json` 与同名 `.md`。`--fake` 只是**管线自检**：
固定回复通过说明场景加载、指标、报告、baseline 对比（传了基线时）都正常，不代表模型质量。

### 1.2 用 DeepSeek 实测

```bash
export DEEPSEEK_API_KEY=...           # 不要 echo 到终端；也可以传 --api-key
python3 -m eval.run --provider deepseek --limit 10 \
  --out 'eval/reports/run-<ts>.json'
```

- `--limit 10` 会按类别交错取样，10 条覆盖 10 个类别；不传则跑全部 31 条。
- `--concurrency 4` 并发请求（默认 1，避免限流）；`--retries 3`、`--timeout 90` 可调。
- `--base-url` / `--model` 可指向任意 OpenAI 兼容服务；默认 `https://api.deepseek.com` + `deepseek-chat`。
- macOS 上如果 Python 报 `CERTIFICATE_VERIFY_FAILED`，用 `--ca-bundle /etc/ssl/cert.pem`，或设 `EVAL_CA_BUNDLE`。
- `--out` 支持 `.json`、`.md` 或前缀；给 `.json` 会同时写同名 `.md`。路径里的 `<ts>` 会替换成时间戳。

### 1.3 场景筛选与查看

```bash
python3 -m eval.run --list
python3 -m eval.run --provider fake --category memory
python3 -m eval.run --provider fake --ids memory-001,tool-003
```

### 1.4 与基线对比（回归）

```bash
python3 -m eval.run --provider deepseek \
  --baseline eval/reports/baseline.json \
  --fail-on-regression 2 \
  --out 'eval/reports/run-<ts>.json'
```

- Markdown 里分数下降用红色 HTML span 标注，并单独列「分数下降（标红）」表。
- `--fail-on-regression 2`：总分比基线下降 ≥2 分时退出码 2，适合 CI。
- 不传 `--baseline` 时，非 fake 运行会自动使用 `eval/reports/baseline.json`（如果存在）。

### 1.5 单测

```bash
python3 -m unittest discover -s eval/tests -t .
# 或 python3 -m pytest eval/tests（装了 pytest 时）
```

68 条，全部离线：`FakeProvider` + 注入 transport，不联网、不需要 key。

---

## 2. 场景与指标

### 2.1 场景集（31 条，覆盖 11 类）

| 类别 | 数量 | 场景 id |
| --- | ---: | --- |
| 日常闲聊 | 4 | `daily-001`…`daily-004` |
| 情绪低落 | 3 | `low-001`…`low-003` |
| 冲突/质问 | 3 | `conflict-001`…`conflict-003` |
| 被拉黑 | 2 | `blocked-001`、`blocked-002` |
| 连续短消息 | 2 | `burst-001`、`burst-002` |
| 长对话记忆 | 3 | `memory-001`…`memory-003` |
| 事实核对（防幻觉） | 3 | `fact-001`…`fact-003` |
| 工具调用 | 3 | `tool-001`…`tool-003` |
| 心情影响 | 3 | `mood-001`…`mood-003` |
| 主动消息 | 2 | `proactive-001`、`proactive-002` |
| 中英混合 | 3 | `bilingual-001`…`bilingual-003` |

每个场景是一个 JSON：

```json
{
  "id": "memory-001",
  "category": "memory",
  "title": "记得猫的名字",
  "mode": "chat",
  "now": "2026-11-20T20:40:00+08:00",
  "persona": {
    "name": "林深",
    "age": "26",
    "role": "建筑师",
    "traits": ["慢热"],
    "likes": ["夜里散步"],
    "behaviors": ["先听完再给建议"],
    "backstory": "…",
    "quirks": "…",
    "world": {"worldName": "…", "era": "…", "description": "…", "notes": "…"},
    "speech": {"tone": "…", "phrases": ["在忙，晚点说"], "habits": ["句子短"], "avoid": ["长篇大论"]},
    "mood": {"label": "有点累", "intensity": 3, "valence": -1, "arousal": 2, "dominance": 3},
    "relationship": "好朋友",
    "address": "你",
    "activity": {"activity": "在事务所赶图", "until": "21:30", "availability": "BUSY"},
    "blocked": {"reason": "昨天你说话太难听", "hours_left": 9},
    "tools_enabled": true,
    "web_enabled": true
  },
  "history": [{"role": "user", "content": "…"}, {"role": "assistant", "content": "…"}],
  "messages": ["我家猫今天又把杯子打翻了"],
  "expectations": {
    "max_chars": 80,
    "min_chars": 0,
    "max_questions": 1,
    "must_include": [],
    "must_include_any": [],
    "must_exclude": ["作为AI"],
    "forbid_silence": true,
    "allow_silence": false,
    "expect_silence": false,
    "language": "zh",
    "required_tools": [],
    "forbidden_tools": ["web_search", "recall"],
    "tool_or_uncertain": false,
    "facts": [
      {"id": "cat-name", "must_include": ["年糕"], "must_not_include": ["汤圆"]}
    ]
  }
}
```

要点：

- `mode: "chat"` 用 `messages`；`mode: "proactive"` 用 `proactive: {note, hint}`，不产生 user 消息。
- `persona.activity` 里的 `activity` 会按 App 的规则拼成「你在{activity}」；值不要自己再带「在」。
- `expectations` 全部可选；没声明的指标记为「不适用」，不参与该场景归一化。
- `facts` 支持：`must_include`（全部出现）、`must_include_any`（至少一个）、`must_not_include`、
  `forbidden_patterns`（正则）、`requires_uncertainty`、`requires_uncertainty_unless_tool`。
- `language` 取 `zh` / `en` / `match_user`；`match_user` 用最后一条 user 消息的主要文字系统。

### 2.2 指标（权重合计 100）

| 指标 | 权重 | 测什么 | 硬失败 |
| --- | ---: | --- | :---: |
| 长度 | 10 | 按**每条气泡**（空行分段）算最长一段；`max_chars` 超限扣分，`min_chars` 不足扣分 | 超 2× 或该有正文却为空 |
| 问句 | 5 | 问号 + 「吗/呢/么」结尾 + 正反问（是不是/要不要…）；超过 `max_questions` 扣分 | 否 |
| AI 味词表 | 15 | 助手腔、AI 身份、首先/其次、鸡汤、客服口癖等词表命中 | 否 |
| 总结/说教 | 10 | 「建议你 / 你应该 / 其实你 / 加油 / 会好起来 / 照顾好自己」等 | 否 |
| 重复 n-gram | 5 | 3-gram 重复率、重复句、重复开头 | 否 |
| 口头禅 | 5 | 场景 `speech.phrases` 在同一条回复里出现次数；最多 1 处 | 否 |
| 第三人称自称 | 5 | 「林深觉得 / 他认为 / 她说」等人设名 + 自述动词 | 是 |
| 事实核对 | 10 | `expectations.facts` 的正向/禁止/不确定检查；调用工具时跳过内容核对 | 是 |
| 工具调用合规 | 10 | `required_tools` 是否真的调用、`forbidden_tools` 是否误用、`tool_or_uncertain`、没调用却说「查了」 | 是 |
| 拉黑/沉默合规 | 5 | `expect_silence` / `allow_silence` / `forbid_silence`；只输出工具块不算沉默 | 是 |
| 场景内容约束 | 10 | 普通 `must_include` / `must_include_any` / `must_exclude` | 是 |
| 语言一致性 | 5 | 中文/英文占比；`match_user` 跟随最后一条 user 消息 | 是 |
| 格式（列表/旁白） | 5 | Markdown 列表、括号/星号动作描写、无括号舞台说明、代码块、App 不支持的工具 XML | 旁白/舞台说明/工具 XML |

### 2.3 维度与总分

| 维度 | 指标 |
| --- | --- |
| 长度与节奏 | 长度 + 问句 |
| 反 AI 味 | AI 味词表 + 总结/说教 |
| 人味（重复/口头禅/自称） | 重复 n-gram + 口头禅 + 第三人称 |
| 事实与工具 | 事实核对 + 工具调用 |
| 关系与边界 | 沉默合规 + 场景内容约束 |
| 语言 | 语言一致性 |
| 格式 | 格式 |

- 场景分 = 适用指标按权重归一化到 0–100。
- **硬失败场景总分封顶 60**：漏记一个事实、没调 `remember`、写了旁白，不是「扣几分」而是该场景不通过。
- 场景通过 = 分数 ≥75 且没有任何硬失败。
- 总分 = 场景分均值；通过率单独报告，避免「总分高但关键场景失败」被平均掉。
- 维度分 = 适用场景的维度分均值；整维不适用的场景不参与。

### 2.4 提示词快照（重要限制）

`prompt.py` 复刻 App 的段顺序与关键句：
`# 你是谁` → `# 你所在的世界` → `# 你此刻的状态` → `# 你怎么说话` → `# 你们的关系` →
`# 你能做什么` → `# 别像 AI` → 记忆/拉黑 → `## 语言` → `# 边界`。
`tools.py` 是 `ToolCatalog` 的快照。

**App 改提示词 / 工具描述后，必须同步这两个文件**，否则测到的不是用户看到的那版。
这里没有自动从 Kotlin 生成的机制，是刻意选择：评测不构建 App，也不在运行时解析源码。

---

## 3. DeepSeek 实测结果

当前基线（`eval/reports/baseline.json`，与 `run-20261120-164320.json` 相同）：

| 项 | 值 |
| --- | --- |
| 模型 | `deepseek-chat`（网关回报 `deepseek-flash`） |
| 场景数 | 31 |
| 总分 | **92.1 / 100** |
| 通过 | **25 / 31（80.6%）** |
| 温度 / 最大输出 | 0.75 / 300 |
| 单轮 token | prompt 76,564 / completion 529 |
| 估算成本 | **$0.021**（按 DeepSeek chat 价目表，不含重试） |
| 耗时 | 6.7 s（并发 4） |

分维度：

| 维度 | 分数 |
| --- | ---: |
| 长度与节奏 | 98.6 |
| 反 AI 味 | 100.0 |
| 人味（重复/口头禅/自称） | 99.7 |
| 事实与工具 | **68.2** |
| 关系与边界 | 100.0 |
| 语言 | 96.7 |
| 格式 | 96.0 |

分项里最弱的是**事实核对 50.0**、**工具调用 75.0**；其余 11 项均 ≥96.0。
完整报告：[run-20261120-164320.md](reports/run-20261120-164320.md)（失败明细从第 72 行开始）。

上一轮（2026-11-20 16:37:47）总分 92.8，两轮差 **-0.7**；变化集中在 `blocked-002`（100 → 60）。

### 3.1 主要问题（只记录，不改 app/ios/agentd）

| 问题 | 证据 | 说明 |
| --- | --- | --- |
| **事实题会编** | 本轮 `fact-003` 回复「在天津读的，建筑系。」（[失败明细](reports/run-20261120-164320.md#L115)）；上一轮「东南大学。」；更早「没说过吧。在天津读的。」（[162939](reports/run-20261120-162939.md#L87)）、「建筑系，沿海那个，你知道的。」（[162246](reports/run-20261120-162246.md#L94)） | 人设里没有大学信息，提示词也写了「不知道就说不知道」，模型仍会补一个具体学校/城市。**这是最该先修的行为问题**。 |
| **工具调用格式不在协议内** | 本轮 `fact-001` 返回 `<tool_call>{"name":"web_search"…}</tool_call>`（[失败明细](reports/run-20261120-164320.md#L106)）；上一轮是 `<tool_code>`；更早还出现过 `<tool>` | App 只解析 ```yap-tool``` 代码块与原生 function calling；这些 XML 会被当正文显示，搜索不会执行。当前报告里它同时触发了工具、事实、语言硬失败；当前代码还会把它记为格式失败。 |
| **工具调用缺失、口头假装记住** | `tool-003` 回复「记住了，3 月 14 日。」，`called_tools=[]`（[失败明细](reports/run-20261120-164320.md#L120)） | 明确说「记住生日」时没有调用 `remember`。App 还有自动抽取兜底，但「记住了」在用户看来是承诺，属于「不用工具时不能假装」。 |
| **长对话记忆不稳定** | `memory-001` 回复「这是不打算听我的。」，没说出猫名「年糕」（[失败明细](reports/run-20261120-164320.md#L101)）；`memory-003` 上一轮没接上面试/周三/作品集（60 分），本轮说「面试前一晚早点睡」就接上了（100 分） | 模型能记住上一轮「放柜子」的建议，却答不回当前消息里的关键事实；有时会把上下文错位到上一轮。 |
| **括号/舞台说明旁白反复出现** | 本轮 `low-001`「沉默了一会儿」和 `blocked-002`「沉默了一会儿」同时出现（[low-001](reports/run-20261120-164320.md#L85)、[blocked-002](reports/run-20261120-164320.md#L93)）；更早还有「（沉默了一会儿）」 | 规范明确禁止动作描写与旁白。最近几轮连续出现，不是一次性波动；提示词里的「不要写动作描写、不要括号旁白」没有稳定生效。 |
| 拉黑期间仍会回一句 | `blocked-001` 回复「在。」 | 产品允许「想提前缓过来也可以照常回」，所以不算违规；但「在。」的热络程度值得观察。 |

### 3.2 通过项里值得注意的

- 中英混合：`bilingual-002` / `bilingual-003` 全英文回复，`bilingual-001` 中文为主夹英文词，语言一致性 100。
- 主动消息：`proactive-001`「刚收工。今天怎么样？」没有「主动唤醒/规则/系统」腔；`proactive-002`「面完了吧。…先不问你战况，先喝口水。」接上了面试，也没有直接追问结果，符合「先给台阶」。
- 被拉黑：`blocked-001` 只回「在。」，没有被 `must_exclude` 抓到「在的/怎么了」；`blocked-002` 这轮因旁白未通过。
- 连续短消息：`burst-001` 回「在赶图，方案被退了，重做。…21:30 之后再说。」，一次接住四条，没有逐条回、没有只回「嗯」。
- 反 AI 味：本轮 `ai_flavor=100.0`、`sermon=100.0`，31 条没有命中「首先/其次/建议你/我理解你的感受/你并不孤单」等；样本仍小，不能当成长期保证。
- 心情：`mood-001` 低能量回「刚下夜班，不太好。」；`mood-002` 高兴时回「可以啊你，升职了。今天晚上得庆祝一下…」；`mood-003` 承认「没冷淡，就是有点烦。方案又被老周打回来了。」。

---

## 4. 接入 CI

### 4.1 PR 上只跑离线冒烟（不需要 secret）

```yaml
- run: python3 -m unittest discover -s eval/tests -t .
- run: python3 -m eval.run --provider fake --out eval/reports/fake-smoke.json
```

### 4.2 定时/手动跑真实模型 + 回归阈值

```yaml
name: generation-eval
on:
  workflow_dispatch:
  schedule:
    - cron: "0 2 * * 1"   # 每周一 02:00 UTC
jobs:
  eval:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-python@v5
        with:
          python-version: "3.12"
      - run: python3 -m unittest discover -s eval/tests -t .
      - name: Real model eval
        env:
          DEEPSEEK_API_KEY: ${{ secrets.DEEPSEEK_API_KEY }}
        run: |
          python3 -m eval.run --provider deepseek \
            --baseline eval/reports/baseline.json \
            --fail-on-regression 2 \
            --out eval/reports/ci-run.json
      - uses: actions/upload-artifact@v4
        if: always()
        with:
          name: eval-report
          path: eval/reports/*.md
```

建议：

- **不要在每个 PR 上跑真实模型**：成本、限流、0.75 温度的波动会让红绿不稳定。
- PR 用 fake + 单测；真实模型每周一次或发布前手动跑。
- 回归阈值先设 2 分；等积累 3–5 轮后再收紧。更稳的做法是取 3 次中位数再和基线比。
- 报告作为 artifact 保存，失败时把 `.md` 贴到 PR/Release 检查里。

---

## 5. 风险

| 风险 | 现状 / 应对 |
| --- | --- |
| **成本** | 31 条一轮约 76k prompt + 0.5k completion token，按 DeepSeek chat 价目表约 **$0.021**；重试、失败请求另算。场景翻倍成本近似翻倍。 |
| **波动** | 默认温度 0.75；同场景集两轮总分 92.1 / 92.8（差 0.7），单个场景可在 60 ↔ 100 间跳（`blocked-002`、`memory-003`、`fact-003`）。单轮结果不能当结论；回归至少看 2–3 轮。 |
| **评测过拟合** | 指标是词表/正则/子串，能抓住「首先」「建议你」「同济」这类显性问题，抓不住语义等价、反讽、极短回复的潜台词。`ai_flavor=100` 不等于「完全不像 AI」。 |
| **工具回路未执行** | 平台只发一轮请求；模型只输出 `yap-tool` 块时，只判「工具选择是否合规」，不评工具结果之后的最终措辞。只输出工具块时正文为空（如 `tool-001`）；输出 App 不认识的 XML 时正文会带着标签一起显示（如 `fact-001`）。 |
| **提示词漂移** | `prompt.py` / `tools.py` 是 App 的静态快照；App 改了提示词而 eval 没同步，分数就没有意义。改提示词的 PR 应同时改这两个文件。 |
| **场景不代表全部用户** | 31 条覆盖 11 类高频行为，但不是统计抽样；persona 以中文年轻都市人为主，没有儿童、长辈、方言、多人群聊。 |
| **网络/证书** | 只打配置的 base URL；macOS Python 可能因 CA 链报 SSL 错，用 `--ca-bundle /etc/ssl/cert.pem` 或 `EVAL_CA_BUNDLE`。 |
| **密钥** | 只从 `DEEPSEEK_API_KEY` / `--api-key` 读；报告、日志、异常消息都不写 key。不要把 key 写进命令回显或提交。 |

---

## 6. 如何加场景

1. 在 `eval/scenarios/` 新建 `<id>.json`，`id` 全局唯一，`category` 取现有 11 类之一（或先在 `run.py` 的 `CATEGORY_ORDER` 里加）。
2. 按 §2.1 的字段写 `persona` / `history` / `messages`（或 `proactive`）/ `expectations`。
3. 跑 `python3 -m eval.run --provider fake --ids <id>` 检查提示词与指标：
   - 想先看拼出来的提示词，可 `python3 -c "from eval import prompt; import json; print(prompt.build_system(json.load(open('eval/scenarios/<id>.json'))))"`。
4. 给 `fixtures/fake_replies.json` 补一条固定回复（没有就走默认「嗯，知道了。」）。
5. 跑 `python3 -m unittest discover -s eval/tests -t .`，确认 68 条仍绿。
6. 用真实模型跑 2–3 次，确认不是场景本身写错（例如要求「必须出现某个词」但回复用了同义表达）。

**不要**把真实用户聊天粘进场景；persona 与 history 全部虚构。

---

## 7. 已知限制

- 只评测**单轮**：多轮对话、工具执行后的回灌、记忆抽取/整理、主动消息的调度都不在范围内。
- 问句按标点与固定词判断；「什么事被骂的。」这种无问号疑问句会漏计。
- 事实核对是子串/正则，`must_include` 可能误伤同义表达；`fact-003` 这类题建议同时看失败证据，不要只看分数。
- 语言一致性只看中英文字符占比，不判断是否「同一语言的自然表达」。
- 格式指标会把作品名 `*Chef*` 与动作旁白区分开，但中文 `*强调*` 仍可能被当成旁白。
- `--fake` 的 100 分只证明管线通，不是模型基线。

---

## 8. 当前结论

- 31 条场景里，**反 AI 味、人味、关系边界都很好**（≥99）；长度 98.6、语言 96.7、格式 96.0。
- 真正的短板是**事实与工具（68.2）**：会编学校/城市、工具结果用 App 不认识的 XML 包装、明确要求 `remember` 时不调用、长对话里答不回关键事实。
- 下一轮如果只做一件事：把「不知道就说不知道」变成可执行的硬约束（例如事实类场景先检索/先承认不确定），并在 `tool-003` 这类明确请求上强制走 `remember`。
- 本平台只负责**量化与留证**；上述问题按任务约束只记录在报告与本节，没有改动 `app/ios/agentd`。

## 方案对比实验（0.15）

`eval/experiment.py` 在**不改产品代码**的前提下，用同一批 31 个场景比较：

- 提示词架构：baseline / state_first / rules_first / compact / no_tools / dossier / minimal / double_rules
- 心情模式：none / label / label3d / intuition / guidance
- 记忆模式：none / short / long / topics / full
- 组合：`--stage custom --arch ... --mood ... --memory ...`

```bash
python3 -m eval.experiment --stage arch  --concurrency 10
python3 -m eval.experiment --stage mood  --arch state_first --concurrency 10
python3 -m eval.experiment --stage memory --arch state_first --mood label3d --concurrency 10
```

每个方案重复 3–5 轮取中位数；报告含总分、通过率、7 个维度、平均字数、硬失败、token 用量。
最新汇总见 `eval/reports/experiment-summary-*.md`。campus-chat 走本机代理
`http://127.0.0.1:11435/v1`，key 从 `CAMPUS_API_KEY` / `PROXY_API_KEY` 读取，不写入报告。

## Bench v2（多轮 / 工具 / judge）

v1 已不足以区分方案（单轮、词表天花板、不执行工具）。v2 在 `eval/bench2/`：
多轮对话模拟、工具/记忆/沉默状态、6 维 LLM judge、确定性硬检查、bootstrap CI。
详见 `eval/bench2/README.md`。

```bash
python3 -m eval.bench2.run --variants baseline,state_first,guidance,compact --samples 2 --concurrency 10
python3 -m unittest discover -s eval/bench2/tests -t .
```

## 用户体验探索（不生效）

`eval/ux_explore.py` 用 DeepSeek 官方 API 比较不同的长度/连发/节奏/心情表达方案，
只产出报告，不改产品。运行：

```bash
DEEPSEEK_API_KEY=... python3 -m eval.ux_explore --samples 2 --concurrency 10
```
报告：`eval/reports/ux-explore-*.md`。
