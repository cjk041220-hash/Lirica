"""一次性清理：删除自动化测试在**生产库**里留下的残留，绝不动真实用户数据。

删除目标（精确匹配，仅测试痕迹）：
- memories：content 以 "verify_tools" 开头 或 为 "喜欢喝椰子水"（E2E 测试塞入）
- diaries ：content 以 "test diary" 开头 或 以 "verify_tools" 开头

用法：PYTHONPATH=backend .venv/Scripts/python scripts/cleanup_test_residue.py
"""
import asyncio
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "backend"))
import aiosqlite
from database.core import DB_PATH


async def main():
    conn = await aiosqlite.connect(str(DB_PATH))
    try:
        await conn.execute("PRAGMA journal_mode=WAL")

        cur = await conn.execute("SELECT id, category, content FROM memories")
        mems = await cur.fetchall()
        del_mem = [m for m in mems if (m[2] or "").startswith("verify_tools") or m[2] == "喜欢喝椰子水"]
        for mid, cat, content in del_mem:
            await conn.execute("DELETE FROM memories WHERE id = ?", (mid,))
            print(f"  删记忆 [{mid[:8]}] cat={cat} | {content}")

        cur = await conn.execute("SELECT date, content FROM diaries")
        dias = await cur.fetchall()
        del_dia = [d for d in dias if (d[1] or "").startswith("test diary") or (d[1] or "").startswith("verify_tools")]
        for date, content in del_dia:
            await conn.execute("DELETE FROM diaries WHERE date = ?", (date,))
            print(f"  删日记 {date} | {content}")

        await conn.commit()

        # 复核
        cur = await conn.execute("SELECT COUNT(*) FROM memories WHERE status='active'")
        mc = (await cur.fetchone())[0]
        cur = await conn.execute("SELECT COUNT(*) FROM diaries")
        dc = (await cur.fetchone())[0]
        print(f"\n清理后：记忆 {mc} 条，日记 {dc} 篇")
    finally:
        await conn.close()


if __name__ == "__main__":
    asyncio.run(main())
