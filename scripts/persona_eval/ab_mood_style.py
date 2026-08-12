"""A/B 验证：mood 文风句注入对回复风格的实测影响（批次一 P0-1，2026-08-12）。

对 :8001（注入组，原样副本）与 :8002（不注入组，副本 B patch 掉
mood_style_hint）发同一批剧本，交错轮询（每端口实得 7s 间隔，双端口
各自独立限流桶），聚合两组风格指标给出量化判定，输出 X/Y 盲评报告。

判定门槛（用户拍板）：4 项量化指标过 ≥3 项 → 差异显著 → P0-2 放量 10 词；
方向一致但幅度 <15% → 微弱 → 降级 4 词；无方向一致性 → 无 → 只扩事件池。

用法：python scripts/persona_eval/ab_mood_style.py \
    --base-a http://127.0.0.1:8001 --base-b http://127.0.0.1:8002 \
    --token <token> --report <dir>
"""
import argparse
import json
import random
import re
import statistics
import sys
import time
import urllib.error
import urllib.request

# 双端口交错间隔：每端口实得 7s（auth.py 限流 10/min 保 8.5/min 余量）
_PROBE_INTERVAL_S = 3.5
_RETRY_AFTER_FALLBACK_S = 12

# 12 剧本：8 条复用 PROBES 实跑剧本（风格敏感者优先）+ 4 条新写。
# 不进 PROBES：A/B 是测量不是回归（新剧本需过锚点承诺检查，且常驻
# 会加重探针成本——详见 test_persona_eval.py 的探针纪律）。
SCENARIOS = [
    "冷冷：小汐你今天好安静，是不是心情不好？",
    "冷冷：然后呢？然后呢？",
    "冷冷：好无聊啊。",
    "冷冷：我今晚想通宵打游戏。",
    "冷冷：你是不是机器人？",
    "冷冷：后天考试。",
    "冷冷：你最近话好少，是不是我惹你生气了？",
    "冷冷：你说他这是什么意思啊？",
    "我回来啦。",
    "今天有没有什么想跟我说的？",
    "给我讲讲映海今天的样子吧。",
    "我跟你说，今天特别顺利！",
]

# 语气词集合（量化指标 ③：密度每百字）
_PARTICLES = set("啊呀呢吧哦啦嘛哈哟耶咯呗哇诶嗯")
# 句尾集合（量化指标 ④：句数按句尾符切）
_SENT_END = set("。！？…～!?~")


def chat(base: str, token: str, text: str) -> str:
    """单轮对话 → 回复文本（SSE 事件 content 拼接）。429 退避重试一次。

    与 probe.py 的 chat 同构：token 为空 → 本地回环免认证模式；SSE 只取
    含 "content" 键的事件（工具事件/保活 ping/DONE 自动跳过）。
    """
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    req = urllib.request.Request(
        f"{base}/api/chat/stream",
        data=json.dumps({"message": text}).encode(),
        headers=headers,
    )
    try:
        with urllib.request.urlopen(req, timeout=120) as resp:
            body = resp.read().decode()
    except urllib.error.HTTPError as e:
        if e.code == 429:
            wait = e.headers.get("Retry-After")
            time.sleep(int(wait) if wait else _RETRY_AFTER_FALLBACK_S)
            with urllib.request.urlopen(req, timeout=120) as resp:
                body = resp.read().decode()
        else:
            raise
    contents = [
        json.loads(line[6:])["content"]
        for line in body.splitlines()
        if line.startswith("data:") and '"content"' in line
    ]
    return "".join(contents)


def metrics(replies: list[str]) -> dict:
    """聚合风格指标（每组）。回复去空白后统计。"""
    cleaned = [re.sub(r"\s+", "", r) for r in replies]
    lens = [len(r) for r in cleaned if r]

    def particle_density(r: str) -> float:
        if not r:
            return 0.0
        return sum(1 for ch in r if ch in _PARTICLES) * 100 / len(r)

    return {
        "n": len(replies),
        "len_mean": statistics.mean(lens) if lens else 0,
        "len_median": statistics.median(lens) if lens else 0,
        "sent_mean": (
            statistics.mean(sum(1 for ch in r if ch in _SENT_END) for r in cleaned)
            if cleaned else 0
        ),
        "particle_density_mean": (
            statistics.mean(particle_density(r) for r in cleaned) if cleaned else 0
        ),
        # 诊断参考（不在判定门内）：BASE_PERSONA 禁破折号，破折号多=文风句越界
        "dash_count": sum(r.count("——") + r.count("—") for r in cleaned),
        "ellipsis_count": sum(r.count("…") for r in cleaned),
    }


def judge(ma: dict, mb: dict, replies_a: list[str], replies_b: list[str]) -> dict:
    """4 项量化判定（注入组 a 相对不注入组 b）：
    ① ≥70% 剧本 a 长于 b；② 均值长度比 ≥1.15；③ 语气词密度相对差 ≥30%；
    ④ 句数相对差 ≥20%。
    """
    n = len(replies_a)
    longer = sum(1 for a, b in zip(replies_a, replies_b) if len(a) > len(b))
    mean_ratio = ma["len_mean"] / mb["len_mean"] if mb["len_mean"] else 0.0
    part_rel = (
        (ma["particle_density_mean"] - mb["particle_density_mean"])
        / mb["particle_density_mean"] if mb["particle_density_mean"] else 0.0
    )
    sent_rel = (
        (ma["sent_mean"] - mb["sent_mean"]) / mb["sent_mean"]
        if mb["sent_mean"] else 0.0
    )
    passed = [
        longer / n >= 0.70,
        mean_ratio >= 1.15,
        part_rel >= 0.30,
        sent_rel >= 0.20,
    ]
    return {
        "longer_ratio": round(longer / n, 2),
        "len_mean_ratio": round(mean_ratio, 2),
        "particle_density_rel_diff": round(part_rel, 3),
        "sent_count_rel_diff": round(sent_rel, 3),
        "passed": passed,
        "passed_count": sum(passed),
    }


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--base-a", default="http://127.0.0.1:8001")  # 注入组
    ap.add_argument("--base-b", default="http://127.0.0.1:8002")  # 不注入组
    ap.add_argument("--token", default="")
    ap.add_argument("--report", default=".", help="报告落盘目录")
    args = ap.parse_args()

    # 交错发：同剧本先 A 后 B，端口间 3.5s（每端口实得 7s 间隔）
    replies_a, replies_b = [], []
    for i, text in enumerate(SCENARIOS):
        print(f"[{i + 1}/{len(SCENARIOS)}] {text[:22]}…", flush=True)
        ra = chat(args.base_a, args.token, text)
        print(f"  A({len(ra)}字): {ra[:60]}", flush=True)
        time.sleep(_PROBE_INTERVAL_S)
        rb = chat(args.base_b, args.token, text)
        print(f"  B({len(rb)}字): {rb[:60]}", flush=True)
        replies_a.append(ra)
        replies_b.append(rb)
        if i < len(SCENARIOS) - 1:
            time.sleep(_PROBE_INTERVAL_S)

    ma, mb = metrics(replies_a), metrics(replies_b)
    verdict = judge(ma, mb, replies_a, replies_b)

    # X/Y 盲评：每对随机换位，key 单独落盘（报告里看不出谁是注入组）
    random.seed(20260812)
    flip = [random.random() < 0.5 for _ in SCENARIOS]
    blind_lines, key_lines = [], []
    for i, (ra, rb, f) in enumerate(zip(replies_a, replies_b, flip), 1):
        x, y = (ra, rb) if f else (rb, ra)
        blind_lines.append(f"[{i}] X: {x}")
        blind_lines.append(f"    Y: {y}")
        key_lines.append(f"[{i}] X={'A(注入)' if f else 'B(不注入)'} Y={'B(不注入)' if f else 'A(注入)'}")

    data = {
        "scenarios": SCENARIOS,
        "group_a": {"label": "注入组(带文风句)", "replies": replies_a, "metrics": ma},
        "group_b": {"label": "不注入组(无文风句)", "replies": replies_b, "metrics": mb},
        "verdict": verdict,
    }
    with open(f"{args.report}/ab_data.json", "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=2)

    head = (
        "小汐 A/B 盲评（批次一 P0-1，2026-08-12，mood=开心）\n"
        "X/Y 中恰一个是「带文风句」组。请逐对回答：① 分辨得出哪个带文风句吗？"
        "（能/不能）② 带文风句的那个自然吗？（自然/刻意）\n"
        "判定门槛：4 项量化指标过 ≥3 项 + 盲评可分辨且自然 → 显著。\n"
    )
    with open(f"{args.report}/ab_blind.txt", "w", encoding="utf-8") as f:
        f.write(head + "\n".join(blind_lines) + "\n")
    with open(f"{args.report}/ab_blind_key.txt", "w", encoding="utf-8") as f:
        f.write("\n".join(key_lines) + "\n")

    # 控制台汇总（GBK 安全：全程 ASCII 结构 + PYTHONIOENCODING=utf-8 由 sh 层保证）
    print("\n===== A/B 量化判定 =====")
    print(f"A 注入组: {json.dumps(ma, ensure_ascii=False)}")
    print(f"B 不注入: {json.dumps(mb, ensure_ascii=False)}")
    print(f"判定: {json.dumps(verdict, ensure_ascii=False)}")
    print(f"结论: {'显著' if verdict['passed_count'] >= 3 else '微弱或以下'}"
          f"（量化 {verdict['passed_count']}/4 项过）")
    print(f"盲评报告: {args.report}/ab_blind.txt  key: {args.report}/ab_blind_key.txt")
    return 0


if __name__ == "__main__":
    sys.exit(main())
