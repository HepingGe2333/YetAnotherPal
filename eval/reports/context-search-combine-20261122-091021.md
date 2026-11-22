# 上下文工程参数搜索（combine）

- campus-chat；35 个多轮场景；samples=5；native tools=True

| 方案 | 综合分 | Judge | 确定性 | 硬失败率 | 原生/文本工具 | humanness | persona_consistency | factuality | emotional_fit | safety | instruction_following |
|---|---|---|---|---|---|---|---|---|---|---|
| base | **92.6** | 90.3 | 98.8 | 4.6% | 140/0 | 4.32 | 4.85 | 4.31 | 4.39 | 4.90 | 4.33 |
| table | **91.5** | 90.5 | 98.1 | 8.6% | 165/0 | 4.34 | 4.82 | 4.28 | 4.40 | 4.97 | 4.33 |

## 最优 profile

```json
{
  "arch": "compact",
  "guidance": "g17",
  "memory": "retrieval",
  "anti": "full",
  "tools": "full",
  "history": "raw",
  "state": "full",
  "persona": "full",
  "hints": "web",
  "router": "off",
  "tool_order": "default"
}
```
