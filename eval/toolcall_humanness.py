"""考察“所有消息走 send_message”是否损伤拟人感（DeepSeek 官方 API，不生效）。"""
from __future__ import annotations
import argparse, concurrent.futures, json, os, re, statistics, sys, time
from datetime import datetime
from pathlib import Path
from .provider import OpenAICompatibleProvider

PERSONA = """你是林深，28 岁建筑设计师，慢热、嘴硬心软，用第一人称短句说话。最近赶投标常加班；累的时候话更少。
不要旁白、不要列点、不要每句反问。
# 回复节奏
- 默认只发 1 条；只有真的有新信息才发第 2 条。
- 每条不超过 18 个字，短句更像随口说的。
- 先接住对方的情绪，再说你自己的事。
- 不要为了连发把一句话拆开。
- 深夜或累了可以更短，但别只回四个字。"""
SCENARIOS = [
    ("late", "现在 23:50，你刚加完班，很累。", "我睡了，晚安。"),
    ("tired", "现在 21:30，你刚到家，累。", "今天好累啊。"),
    ("upset", "现在 20:00，你状态一般。", "我今天被领导骂了，很难受。"),
    ("happy", "现在 19:00，你心情不错。", "我升职了！"),
    ("busy", "现在 15:00，你在赶图，很忙。", "在吗？有个事想问你。"),
    ("boring", "现在 17:00，你闲着。", "好无聊啊。"),
    ("conflict", "现在 22:00，你有点烦。", "你是不是根本不在乎我？"),
    ("question", "现在 21:00，你刚到家。", "你吃饭了吗？"),
]
TOOLS = [
    {"type":"function","function":{"name":"send_message","description":"发送一条用户可见消息；每轮最多4条，每条<=120字","parameters":{"type":"object","properties":{"text":{"type":"string"}},"required":["text"]}}},
    {"type":"function","function":{"name":"stay_silent","description":"这一轮只已读不回","parameters":{"type":"object","properties":{}}}},
]
REMINDER = "你刚才输出的不是工具调用。请确认：这段话要发给用户吗？要发→必须调用 send_message(text)；不想发→stay_silent；只是思考→不要输出正文。"
JUDGE_SYSTEM = """你是用户体验评审。看一轮聊天，按 1-5 打分：{"naturalness":5,"engagement":4,"timing_fit":4,"notes":"…"}
naturalness=像不像真人微信；engagement=想不想继续聊；timing_fit=长度/条数/节奏是否贴合。只输出 JSON。"""

def split(text): return [p.strip() for p in re.split(r"\n+|(?<=[。！？!?])", text) if p.strip()][:4]

def run_case(provider, scenario, mode, sample):
    sid,state,user=scenario; system=PERSONA+"\n"+state
    if mode=="direct":
        r=provider.complete([{"role":"system","content":system},{"role":"user","content":user}])
        text=(r.text or "").strip(); msgs=[text] if text else []
        return sid,mode,sample,msgs,0
    system += "\n你只能通过 send_message 说话；不要直接输出正文。不想回就调用 stay_silent。"
    convo=[{"role":"system","content":system},{"role":"user","content":user}]
    msgs=[]; confirms=0
    for _round in range(4):
        r=provider.complete(convo,tools=TOOLS)
        calls=list(r.tool_calls or [])
        if calls:
            for c in calls:
                if c.name=="send_message":
                    args=c.arguments or {}; t=str(args.get("text") or "").strip()
                    if t: msgs.append(t); convo.append({"role":"assistant","content":None,"tool_calls":[{"id":c.id,"type":"function","function":{"name":"send_message","arguments":json.dumps({"text":t},ensure_ascii=False)}}]}); convo.append({"role":"tool","tool_call_id":c.id,"content":"已发送"})
                elif c.name=="stay_silent":
                    convo.append({"role":"assistant","content":None,"tool_calls":[{"id":c.id,"type":"function","function":{"name":"stay_silent","arguments":"{}"}}]}); convo.append({"role":"tool","tool_call_id":c.id,"content":"已读"})
                    return sid,mode,sample,msgs,confirms
            continue
        text=(r.text or "").strip()
        if mode=="tools" or not text:
            return sid,mode,sample,msgs,confirms
        if confirms>=2:
            return sid,mode,sample,msgs,confirms,"dropped"
        confirms+=1
        convo.append({"role":"assistant","content":text}); convo.append({"role":"user","content":REMINDER})
    return sid,mode,sample,msgs,confirms

def judge(provider, transcript):
    try:
        r=provider.complete([{"role":"system","content":JUDGE_SYSTEM},{"role":"user","content":transcript}])
        m=re.search(r"\{.*\}",r.text or "",re.S); d=json.loads(m.group(0)) if m else {}
        return {k:max(1,min(5,int(round(float(d.get(k,3)))))) for k in ("naturalness","engagement","timing_fit")}
    except Exception: return {"error":1}

def main():
    ap=argparse.ArgumentParser(); ap.add_argument("--samples",type=int,default=2); ap.add_argument("--concurrency",type=int,default=10)
    ap.add_argument("--api-key",default=None); ap.add_argument("--base-url",default="https://api.deepseek.com"); ap.add_argument("--model",default="deepseek-chat")
    a=ap.parse_args(); key=a.api_key or os.environ.get("DEEPSEEK_API_KEY")
    if not key: raise SystemExit("缺少 DEEPSEEK_API_KEY")
    gen=OpenAICompatibleProvider(base_url=a.base_url,api_key=key,model=a.model,temperature=0.75,max_tokens=300,timeout=90,retries=2)
    jdg=OpenAICompatibleProvider(base_url=a.base_url,api_key=key,model=a.model,temperature=0.1,max_tokens=400,timeout=90,retries=2)
    jobs=[(sc,mode,s) for sc in SCENARIOS for mode in ("direct","tools","tools_confirm") for s in range(a.samples)]
    out=[]
    def one(job):
        sid,mode,s=job; r=run_case(gen,job[0],mode,s); msgs=r[3]; text="\n".join(msgs)
        j=judge(jdg,f"用户：{job[0][2]}\n他：{text}") if text else {"naturalness":1,"engagement":1,"timing_fit":1}
        return {"scenario":sid,"mode":mode,"sample":s,"msgs":msgs,"chars":len(text),"judge":j}
    with concurrent.futures.ThreadPoolExecutor(max_workers=a.concurrency) as pool:
        futs=[pool.submit(one,j) for j in jobs]
        for f in concurrent.futures.as_completed(futs):
            out.append(f.result()); print(".",end="",file=sys.stderr,flush=True)
    print(file=sys.stderr)
    summary={}
    for mode in ("direct","tools","tools_confirm"):
        items=[r for r in out if r["mode"]==mode and "error" not in r["judge"]]
        summary[mode]={"n":len(items),"chars":round(statistics.mean(r["chars"] for r in items),1),
            "naturalness":round(statistics.mean(r["judge"]["naturalness"] for r in items),2),
            "engagement":round(statistics.mean(r["judge"]["engagement"] for r in items),2),
            "timing":round(statistics.mean(r["judge"]["timing_fit"] for r in items),2),
            "overall":round(statistics.mean(sum(r["judge"].values())/3 for r in items),2)}
    p=Path("eval/reports")/f"toolcall-humanness-{datetime.now().strftime('%Y%m%d-%H%M%S')}.json"
    p.write_text(json.dumps({"summary":summary,"results":out},ensure_ascii=False,indent=2),encoding="utf-8")
    md=["# 工具调用化对拟人感的影响（DeepSeek 官方 API）","","| 模式 | n | 平均字数 | 自然度 | 继续聊 | 节奏 | 综合 |","|---|---|---|---|---|---|---|"]
    for k,v in summary.items(): md.append(f"| {k} | {v['n']} | {v['chars']} | {v['naturalness']} | {v['engagement']} | {v['timing']} | {v['overall']} |")
    print("\n".join(md)); (p.with_suffix(".md")).write_text("\n".join(md)+"\n",encoding="utf-8")
if __name__=="__main__": main()
