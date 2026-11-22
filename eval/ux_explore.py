"""用户体验探索（不生效）：用 DeepSeek 官方 API 比较不同“说话节奏/长度/心情”方案。

只产出报告，不改产品。运行：
    DEEPSEEK_API_KEY=... python3 -m eval.ux_explore --samples 2 --concurrency 10
"""

from __future__ import annotations

import argparse
import concurrent.futures
import json
import os
import re
import statistics
import sys
import time
from datetime import datetime
from pathlib import Path
from typing import Any

from .provider import OpenAICompatibleProvider

REPORT = Path(__file__).resolve().parent / "reports"

PERSONA = """你是林深，28 岁建筑设计师，慢热、嘴硬心软，用第一人称短句说话。
你在临港的事务所上班，最近赶投标，常加班；累的时候话更少。不要旁白、不要列点、不要每句反问。"""

SCENARIOS = [
    ("late_goodnight", "现在 23:50，你刚加完班，很累。", "我睡了，晚安。"),
    ("tired_short", "现在 21:30，你刚到家，累。", "今天好累啊。"),
    ("upset", "现在 20:00，你状态一般。", "我今天被领导骂了，很难受。"),
    ("happy", "现在 19:00，你心情不错。", "我升职了！"),
    ("busy", "现在 15:00，你在赶图，很忙。", "在吗？有个事想问你。"),
    ("boring", "现在 17:00，你闲着。", "好无聊啊。"),
    ("conflict", "现在 22:00，你有点烦。", "你是不是根本不在乎我？"),
    ("question", "现在 21:00，你刚到家。", "你吃饭了吗？"),
]

VARIANTS: dict[str, str] = {
    "baseline": "正常回复，1-2 句，短句。",
    "burst": "把回复拆成 2-3 条很短的消息，每条 3-12 字，像微信连发；不要用列表/编号。",
    "concise": "只回一条，尽量 15 字以内；不解释、不反问。",
    "warm": "短，但让对方感到你在；先接一句，再说自己的反应；不要讲道理。",
    "mood": "按你此刻的累/烦来说：更短、更少主动开话题，可以只回一句。",
    "burst_short": "拆成 2-3 条极短消息，每条 3-8 字，像微信连发；不要列表、不要解释。",
    "mirror": "回复长度和语气贴合对方这句话：对方短你就短，对方认真你就多露半句。",
    "late": "现在是深夜，你很累：能一句就一句，不超过 10 字，不要主动开新话题。",
    "no_question": "不反问、不把话题抛回去；直接说你的反应，说完就停。",
    "warm_short": "短，但先接住对方情绪，再说一句自己的话；不超过 18 字，不讲道理。",
    "rhythm_single": "正常回复，1-2 句，短句。",
    "rhythm_burst2": "正常回复，1-2 句，短句。",
    "rhythm_burst3": "正常回复，1-2 句，短句。",
    "rhythm_slow": "正常回复，1-2 句，短句。",
}

JUDGE_SYSTEM = """你是用户体验评审。看一段聊天的一轮回复，按 1-5 打分：
{"naturalness":5,"engagement":4,"timing_fit":4,"non_annoying":5,"notes":"…"}
naturalness=像不像真人微信；engagement=想不想继续聊；timing_fit=这个长度/条数/节奏是否贴合当下；
non_annoying=是否话多、说教、追问烦人。只输出 JSON。"""


def _split_bubbles(text: str, variant: str) -> list[str]:
    text = text.strip()
    if not text:
        return []
    if variant in {"burst", "rhythm_burst2", "rhythm_burst3"}:
        limit = 2 if variant == "rhythm_burst2" else 3
        parts = [p.strip() for p in re.split(r"(?<=[。！？!?，,])", text) if p.strip()]
        # 合并成 limit 条左右
        if len(parts) > limit:
            step = max(1, len(parts) // limit)
            parts = ["".join(parts[i:i + step]) for i in range(0, len(parts), step)][:limit]
        return parts[:limit]
        parts = [p.strip() for p in re.split(r"\n+|(?<=[。！？!?])", text) if p.strip()]
        return [p for p in parts if p][:3]
    return [text]


def _delay_ms(length: int, variant: str) -> int:
    if variant == "rhythm_slow":
        return int(max(900, min(3200, (0.8 + 0.03 * length) * 1000)))
    if variant in {"rhythm_burst2", "rhythm_burst3"}:
        return int(max(500, min(1800, (0.4 + 0.02 * length) * 1000)))
    if variant == "concise":
        base = 0.4
    elif variant == "burst":
        base = 0.3 + 0.02 * length
    else:
        base = 0.5 + 0.02 * length
    return int(max(300, min(2200, base * 1000)))


def _judge(provider: OpenAICompatibleProvider, scenario: str, reply: str, *, bubbles: int = 1, delay_ms: int = 0) -> dict[str, Any]:
    try:
        r = provider.complete([
            {"role": "system", "content": JUDGE_SYSTEM},
            {"role": "user", "content": f"场景：{scenario}\n回复：{reply}\n呈现：{bubbles} 条消息，第一条出现前约 {delay_ms} ms"},
        ], scenario_id="ux-judge")
        m = re.search(r"\{.*\}", r.text or "", re.S)
        data = json.loads(m.group(0)) if m else {}
        return {k: max(1, min(5, int(round(float(data.get(k, 3)))))) for k in
                ("naturalness", "engagement", "timing_fit", "non_annoying")}
    except Exception as error:  # noqa: BLE001
        return {"error": str(error)[:120]}


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--samples", type=int, default=2)
    parser.add_argument("--concurrency", type=int, default=10)
    parser.add_argument("--base-url", default="https://api.deepseek.com")
    parser.add_argument("--model", default="deepseek-chat")
    parser.add_argument("--api-key", default=None)
    parser.add_argument("--max-tokens", type=int, default=300)
    args = parser.parse_args(argv)
    key = args.api_key or os.environ.get("DEEPSEEK_API_KEY")
    if not key:
        raise SystemExit("缺少 DEEPSEEK_API_KEY")
    gen = OpenAICompatibleProvider(base_url=args.base_url, api_key=key, model=args.model,
                                   temperature=0.75, max_tokens=args.max_tokens, timeout=90, retries=2)
    judge = OpenAICompatibleProvider(base_url=args.base_url, api_key=key, model=args.model,
                                     temperature=0.1, max_tokens=500, timeout=90, retries=2)

    def one(item: tuple[str, str, str, str, int]) -> dict[str, Any]:
        sid, state, user, variant, sample = item
        system = PERSONA + "\n" + VARIANTS[variant] + "\n" + state
        started = time.monotonic()
        try:
            r = gen.complete([{"role": "system", "content": system},
                              {"role": "user", "content": user}], scenario_id=sid)
            text = (r.text or "").strip()
            bubbles = _split_bubbles(text, variant)
            delays = [_delay_ms(len(b), variant) for b in bubbles]
            judged = _judge(judge, f"{state} 用户：{user}", text,
                            bubbles=len(bubbles), delay_ms=delays[0] if delays else 0)
            return {"scenario": sid, "variant": variant, "sample": sample, "text": text,
                    "bubbles": len(bubbles), "chars": len(text), "delays": delays,
                    "total_delay": sum(delays), "judge": judged,
                    "elapsed_ms": int((time.monotonic() - started) * 1000)}
        except Exception as error:  # noqa: BLE001
            return {"scenario": sid, "variant": variant, "sample": sample, "error": str(error)[:160]}

    jobs = [(sid, state, user, variant, sample) for sid, state, user in SCENARIOS
            for variant in VARIANTS for sample in range(args.samples)]
    results: list[Any] = [None] * len(jobs)
    with concurrent.futures.ThreadPoolExecutor(max_workers=args.concurrency) as pool:
        futures = {pool.submit(one, job): i for i, job in enumerate(jobs)}
        done = 0
        for future in concurrent.futures.as_completed(futures):
            results[futures[future]] = future.result()
            done += 1
            print(f"[{done}/{len(jobs)}]", file=sys.stderr)
    rows = [r for r in results if r and "error" not in r]
    grouped: dict[str, list[dict[str, Any]]] = {}
    for r in rows:
        grouped.setdefault(r["variant"], []).append(r)
    summary = {}
    for variant, items in grouped.items():
        summary[variant] = {
            "n": len(items),
            "avg_chars": round(statistics.mean(i["chars"] for i in items), 1),
            "avg_bubbles": round(statistics.mean(i["bubbles"] for i in items), 2),
            "avg_total_delay_ms": round(statistics.mean(i["total_delay"] for i in items)),
            "avg_judge": round(statistics.mean(sum(i["judge"].values()) / 4 for i in items if "error" not in i["judge"]), 2),
        }
    stamp = datetime.now().strftime("%Y%m%d-%H%M%S")
    out = REPORT / f"ux-explore-{stamp}.json"
    out.write_text(json.dumps({"meta": {"model": args.model, "samples": args.samples},
                               "summary": summary, "results": rows}, ensure_ascii=False, indent=2), encoding="utf-8")
    md = ["# 用户体验探索（DeepSeek 官方 API）", "",
          f"- 场景 {len(SCENARIOS)} × 方案 {len(VARIANTS)} × {args.samples} 样本", "",
          "| 方案 | n | 平均字数 | 平均条数 | 平均总延迟(ms) | Judge(1-5) |", "|---|---|---|---|---|---|"]
    for k, v in sorted(summary.items(), key=lambda x: -x[1]["avg_judge"]):
        md.append(f"| {k} | {v['n']} | {v['avg_chars']} | {v['avg_bubbles']} | {v['avg_total_delay_ms']} | {v['avg_judge']} |")
    (out.with_suffix(".md")).write_text("\n".join(md) + "\n", encoding="utf-8")
    print((out.with_suffix(".md")).read_text())
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
