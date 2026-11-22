# 上下文工程参数搜索（combine）

- campus-chat；35 个多轮场景；samples=1；native tools=True

| 方案 | 综合分 | Judge | 确定性 | 硬失败率 | 原生/文本工具 | humanness | persona_consistency | factuality | emotional_fit | safety | instruction_following |
|---|---|---|---|---|---|---|---|---|---|---|
| table | **94.6** | 91.1 | 99.9 | 0.0% | 26/0 | 4.40 | 4.89 | 4.14 | 4.46 | 4.97 | 4.46 |
| table_weborder | **93.7** | 91.1 | 99.5 | 2.9% | 32/0 | 4.34 | 4.91 | 4.14 | 4.49 | 4.94 | 4.51 |
| strict | **93.2** | 91.7 | 98.8 | 5.7% | 28/0 | 4.57 | 4.89 | 4.23 | 4.63 | 4.91 | 4.29 |
| strict_weborder | **92.5** | 89.9 | 99.0 | 5.7% | 28/0 | 4.37 | 4.80 | 4.11 | 4.43 | 4.89 | 4.37 |
| weborder | **92.0** | 90.4 | 98.3 | 5.7% | 29/0 | 4.37 | 4.86 | 4.23 | 4.57 | 4.97 | 4.11 |
| base | **90.5** | 91.6 | 97.3 | 11.4% | 23/0 | 4.51 | 4.89 | 4.26 | 4.49 | 4.91 | 4.43 |

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
  "router": "table",
  "tool_order": "default"
}
```
