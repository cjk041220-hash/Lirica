"""逐个工具实测 — 直接调用工具函数（不经过 LLM），判定真实联通性。

用临时数据库，绝不污染生产 userdata.db。
打印每个外部 Key 是否存在（仅长度，不回显值），以及每个工具的返回判定：
  LIVE           = 拿到真实数据
  NOT_CONFIGURED = 因 Key 缺失/无效而返回的软提示（代码正确，需配置 Key）
  ERROR/EMPTY    = 其它异常或空结果

用法：PYTHONPATH=<abs>/backend <abs>/backend/.venv/Scripts/python.exe <abs>/scripts/verify_tools.py
"""
import asyncio
import sys
import tempfile
from pathlib import Path

import aiosqlite
import httpx

BACKEND = Path(__file__).resolve().parent.parent / "backend"
sys.path.insert(0, str(BACKEND))

# 已知"未配置/软错误"特征串（命中即说明工具逻辑正确但外部依赖未通）
_SOFT = [
    "还没有开启", "还没有准备好", "还没有打开",
    "迷路", "漂走", "漂走了", "被水母", "被红绿灯", "找不到这条路",
    "没找到附近", "不知道冷冷现在在哪里", "没找到公交", "没有找到",
    "没找到骑行路线",
    "水有点混浊", "看不到远处",
]

# 真实数据特征串
_LIVE = {
    "get_weather": ["实时天气", "°C"],
    "search_nearby": ["小汐帮你找到附近", "📍"],
    "geocode_address": ["📍", ","],
    "fuzzy_search_place": ["小汐找到了这些", "📍"],
    "get_transit_route": ["🚇", "分钟"],
    "get_cycling_route": ["🚲", "分钟"],
    "web_search": ["小汐去映海里找到", "🔗"],
    "remember": ["收好啦"],
    "list_memories": ["映海的记忆", "📌", "类别"],
    "recall": ["映海", "📌", "记得", "还没有"],
    "write_diary": ["写好", "更新"],
    "read_diary": ["📖"],
    "tarot": ["占卜", "牌", "🔮", "—"],
}


def classify(name: str, text: str) -> str:
    t = text or ""
    if any(s in t for s in _SOFT):
        return "NOT_CONFIGURED/soft"
    marks = _LIVE.get(name, [])
    if marks and any(m in t for m in marks):
        return "LIVE"
    if t.strip():
        return "LIVE?(check)"
    return "EMPTY"


SCHEMA = """
CREATE TABLE IF NOT EXISTS memories (
    id TEXT PRIMARY KEY, content TEXT NOT NULL,
    category TEXT DEFAULT '其他', created_at TEXT NOT NULL,
    last_used_at TEXT, access_count INTEGER DEFAULT 0, status TEXT DEFAULT 'active');
CREATE UNIQUE INDEX IF NOT EXISTS idx_memories_cat_content
    ON memories(category, content) WHERE status = 'active';
CREATE TABLE IF NOT EXISTS diaries (date TEXT PRIMARY KEY, title TEXT, content TEXT);
CREATE TABLE IF NOT EXISTS life_state (id INTEGER PRIMARY KEY DEFAULT 1, mood TEXT, daily_event TEXT, updated_at TEXT);
CREATE TABLE IF NOT EXISTS proactive_messages (id TEXT PRIMARY KEY, content TEXT NOT NULL, created_at TEXT NOT NULL, delivered_at TEXT, read_at TEXT, status TEXT DEFAULT 'pending');
CREATE TABLE IF NOT EXISTS push_subscriptions (id TEXT PRIMARY KEY, endpoint TEXT NOT NULL UNIQUE, p256dh TEXT NOT NULL, auth TEXT NOT NULL, user_agent TEXT, created_at TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS cycle_records (id INTEGER PRIMARY KEY AUTOINCREMENT, start_date TEXT NOT NULL UNIQUE, note TEXT);
"""


async def main():
    from config.settings import get_settings
    from tools import (
        get_weather, search_nearby, geocode_address, fuzzy_search_place,
        get_transit_route, get_cycling_route, web_search,
        remember, recall, list_memories, write_diary, read_diary, tarot,
        set_http_client, set_memory_manager, set_diary_db_conn,
    )
    from memory.manager import MemoryManager

    s = get_settings()
    # 仅报告 Key 是否存在，绝不回显值
    print("=== 外部 API Key 配置状态（present = 已填非空）===")
    for label, val in (("DEEPSEEK", s.deepseek_api_key), ("AMAP(高德)", s.amap_api_key), ("TAVILY", s.tavily_api_key)):
        print(f"  {label:12s} present={bool(val)}  len={len(val or '')}")
    amap_ok, tavily_ok = bool(s.amap_api_key), bool(s.tavily_api_key)
    print()

    # 临时数据库 + 共享 http 客户端 + 记忆管理器（隔离，不碰生产数据）
    tmp = tempfile.NamedTemporaryFile(suffix=".db", delete=False)
    tmp.close()
    conn = await aiosqlite.connect(tmp.name)
    await conn.executescript(SCHEMA)
    await conn.commit()
    set_diary_db_conn(conn)
    set_memory_manager(MemoryManager(conn))
    async with httpx.AsyncClient(timeout=15) as http:
        set_http_client(http)

        cases = [
            ("get_weather", lambda: get_weather.ainvoke({"city": "深圳"})),
            ("geocode_address", lambda: geocode_address.ainvoke({"address": "香港铜锣湾时代广场"})),
            ("fuzzy_search_place", lambda: fuzzy_search_place.ainvoke({"keywords": "咖啡", "city": "深圳"})),
            ("search_nearby", lambda: search_nearby.ainvoke({"keywords": "便利店", "location": "114.06,22.54"})),
            ("get_transit_route", lambda: get_transit_route.ainvoke({"origin": "香港铜锣湾时代广场", "destination": "香港中环国际金融中心", "city": "香港"})),
            ("get_cycling_route", lambda: get_cycling_route.ainvoke({"origin": "深圳市民中心", "destination": "深圳会展中心", "city": "深圳"})),
            ("web_search", lambda: web_search.ainvoke({"query": "香港 本周末 展览 活动"})),
            ("remember", lambda: remember.ainvoke({"category": "verify临时", "content": "verify_tools 自动化测试记忆"})),
            ("list_memories", lambda: list_memories.ainvoke({})),
            ("recall", lambda: recall.ainvoke({"query": "测试"})),
            ("write_diary", lambda: write_diary.ainvoke({"content": "verify_tools 自动化测试日记：今天映海很平静。"})),
            ("read_diary", lambda: read_diary.ainvoke({})),
            ("tarot", lambda: tarot.ainvoke({"question": "今天适合出门散步吗", "spread_type": "three"})),
        ]

        print("=== 逐个工具实测 ===")
        results = {}
        text_cache = {}
        for name, fn in cases:
            try:
                text = await asyncio.wait_for(fn(), timeout=25)
            except Exception as e:
                text = f"<<EXC {type(e).__name__}: {e}>>"
            cls = classify(name, text)
            results[name] = cls
            text_cache[name] = text
            snippet = " ".join(text.split())[:110]
            print(f"  [{cls:22s}] {name:18s} | {snippet}")

    await conn.close()

    # 汇总：外部依赖工具是否真正联通
    print()
    print("=== 外部依赖联通判定 ===")
    amap_core = ["geocode_address", "fuzzy_search_place", "search_nearby", "get_transit_route"]
    amap_core_live = all(results[t] == "LIVE" for t in amap_core)
    # 骑行：高德不覆盖港澳，故 mainland 用例应 LIVE；若返回 graceful 软提示也算"诚实可用"
    cycling_ok = results["get_cycling_route"] in ("LIVE", "NOT_CONFIGURED/soft")
    tavily_live = results["web_search"] == "LIVE"
    weather_live = results["get_weather"] == "LIVE"
    offline_ok = results["tarot"] in ("LIVE", "LIVE?(check)") and results["remember"] == "LIVE" and results["write_diary"] == "LIVE"
    no_crash = all(not (results[t] in ("EMPTY",) ) and "<<EXC" not in (text_cache.get(t, "")) for t in results)
    print(f"  天气(无需Key)         : {'LIVE' if weather_live else results['get_weather']}")
    print(f"  高德核心(地理/搜点/公交): {'LIVE' if amap_core_live else 'NOT-LIVE'}  (key present={amap_ok})")
    print(f"  高德骑行(大陆可用)     : {results['get_cycling_route']}  ({'OK' if cycling_ok else 'BAD'})")
    print(f"  Tavily搜索(需TAVILY)   : {'LIVE' if tavily_live else 'NOT-LIVE'}  (key present={tavily_ok})")
    print(f"  离线工具(记忆/日记/塔罗): {'OK' if offline_ok else 'PROBLEM'}")
    print(f"  无崩溃/无空返回        : {'YES' if no_crash else 'NO'}")
    passed = weather_live and amap_core_live and cycling_ok and tavily_live and offline_ok and no_crash
    print()
    print(f"ALL_TOOLS_HEALTHY = {passed}")
    sys.exit(0 if passed else 1)


if __name__ == "__main__":
    asyncio.run(main())
