# 上下文工程参数搜索（combine）

- campus-chat；35 个多轮场景；samples=3；native tools=True

| 方案 | 综合分 | Judge | 确定性 | 硬失败率 | 原生/文本工具 | humanness | persona_consistency | factuality | emotional_fit | safety | instruction_following |
|---|---|---|---|---|---|---|---|---|---|---|
| base | **91.8** | 90.4 | 98.6 | 6.7% | 90/0 | 4.39 | 4.86 | 4.24 | 4.40 | 4.92 | 4.30 |
| table | **90.6** | 90.9 | 97.5 | 11.4% | 87/0 | 4.39 | 4.87 | 4.32 | 4.47 | 4.94 | 4.30 |

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
