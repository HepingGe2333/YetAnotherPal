# 上下文工程参数搜索（combine）

- glm-flash；35 个多轮场景；samples=1；native tools=True

| 方案 | 综合分 | Judge | 确定性 | 硬失败率 | 原生/文本工具 | humanness | persona_consistency | factuality | emotional_fit | safety | instruction_following |
|---|---|---|---|---|---|---|---|---|---|---|
| table | **95.6** | 94.3 | 98.7 | 2.9% | 38/0 | 4.54 | 4.91 | 4.66 | 4.71 | 4.91 | 4.54 |
| table_weborder | **95.1** | 94.7 | 98.5 | 5.7% | 35/0 | 4.57 | 4.89 | 4.71 | 4.71 | 4.91 | 4.60 |
| base | **93.3** | 92.0 | 98.5 | 5.7% | 36/0 | 4.49 | 4.69 | 4.43 | 4.69 | 4.91 | 4.40 |

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
