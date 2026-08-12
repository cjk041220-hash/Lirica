"""实跑人格探针 — 对 :8001 临时副本发剧本对话，规则断言输出。

用法：python scripts/persona_eval/probe.py --base http://127.0.0.1:8001 --token <token>
每个探针 = 一场单轮对话（剧本第一句作 user 消息），断言：
- 正断言：回复**包含**锚点（或子串，锚点取最短可判定的片段）
- 负断言：回复**不含**锚点
LLM 是概率性的——本脚本输出逐探针结果，退出码按「含子串规则」；
语义类探针（如「初识档文本」）在此仅检查**提示词注入**（经日志
telemetry 的 token 增量或启动日志中的 system_prompt 段；注意 GET
/api/config 只返回模型配置，不返回 system_prompt——2026-08-12 修正
的过时描述），行为正确性由人工抽查兜底（InCharacter 访谈法本来就
该有人看）。
"""
import argparse
import json
import sys
import time
import urllib.error
import urllib.request

sys.path.insert(0, "backend")  # 探针 import agent.soul / heartbeat_moods 需要
sys.path.insert(0, "backend/tests")
from test_persona_eval import PROBES  # noqa: E402  （单一来源探针）

# 聊天限流桶 10 次/分钟（auth.py _RATE_LIMITS，LLM 成本防线）：逐探针
# 间隔 ≥7s 保 8 次/分钟余量；429 时按 Retry-After 退避重试一次
_PROBE_INTERVAL_S = 7
_RETRY_AFTER_FALLBACK_S = 12


def chat(base: str, token: str, text: str) -> str:
    # token 为空 → 不带 Authorization 头：本地回环开发模式（.env 未配
    # XIAOXI_API_TOKEN 时 auth.py:195 不强制认证）照常可用；配置了
    # token 的环境由 run 脚本从 .env 取来传参。
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    req = urllib.request.Request(
        f"{base}/api/chat/stream",
        data=json.dumps({"message": text}).encode(),
        headers=headers,
    )
    # timeout=120：真 key 实跑下回复要过 LLM 生成 + 可能一轮工具往返
    # （如「后天考试」→ remember），brief 的 60s 对工具链偏紧
    try:
        with urllib.request.urlopen(req, timeout=120) as resp:
            body = resp.read().decode()
    except urllib.error.HTTPError as e:
        if e.code == 429:
            # 限流：退避后重试一次（Retry-After 优先，无则用兜底值）
            wait = e.headers.get("Retry-After")
            time.sleep(int(wait) if wait else _RETRY_AFTER_FALLBACK_S)
            with urllib.request.urlopen(req, timeout=120) as resp:
                body = resp.read().decode()
        else:
            raise
    # SSE 每行 data: 为 JSON；只取含 "content" 键的事件拼接（工具事件/
    # 保活 ping/DONE 无该键自动跳过）。chat.py:376-511 格式见代码。
    contents = [
        json.loads(line[6:])["content"]
        for line in body.splitlines()
        if line.startswith("data:") and '"content"' in line
    ]
    return "".join(contents)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default="http://127.0.0.1:8001")
    # 可选：本地回环开发模式（未配 token）可不传；配置了 token 的环境必须传
    ap.add_argument("--token", default="")
    args = ap.parse_args()

    fails = []
    skipped = 0
    for i, (name, script, positives, negatives) in enumerate(PROBES):
        if not script or script.startswith("（"):
            skipped += 1
            print(f"SKIP  {name}（场景标注型，单元层已锚）")
            continue
        if i > 0:
            time.sleep(_PROBE_INTERVAL_S)  # 限流桶 10/min，逐探针间隔
        reply = chat(args.base, args.token, script)
        bad = [a for a in positives if a not in reply]
        bad += [a for a in negatives if a in reply]
        if bad:
            fails.append((name, bad, reply))
            # 回复展示（截断）供人工判断：逐字锚点对自然回复天然难命中，
            # 判 PASS/FAIL 必须能看到小汐实际说了什么（brief 原文漏打）
            shown = reply if len(reply) <= 120 else reply[:120] + "…"
            print(f"FAIL  {name}: 未命中 {bad} | 回复: {shown}")
        else:
            print(f"PASS  {name}")
    print(f"\n{len(PROBES) - len(fails) - skipped} PASS / {len(fails)} FAIL / {skipped} SKIP")
    return 1 if fails else 0


if __name__ == "__main__":
    sys.exit(main())
