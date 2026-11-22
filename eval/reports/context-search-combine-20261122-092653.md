# 上下文工程参数搜索（combine）

- glm-flash；35 个多轮场景；samples=3；native tools=True

| 方案 | 综合分 | Judge | 确定性 | 硬失败率 | 原生/文本工具 | humanness | persona_consistency | factuality | emotional_fit | safety | instruction_following |
|---|---|---|---|---|---|---|---|---|---|---|
| base | **94.2** | 94.1 | 98.2 | 6.7% | 99/0 | 4.56 | 4.87 | 4.57 | 4.70 | 4.91 | 4.62 |
| table | **93.1** | 91.9 | 98.2 | 6.7% | 107/0 | 4.40 | 4.72 | 4.53 | 4.58 | 4.95 | 4.37 |

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
