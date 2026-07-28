"""一次性清理：删除自动化测试在**生产库**里留下的残留，绝不动真实用户数据。

⚠️ 不得匹配 E2E 固定夹具数据：「喜欢喝椰子水」是 E2E（scripts/e2e/ui_test.cjs）
断言依赖的生产记忆，仓库内已无任何代码会插入它，把它当测试残留删除会误伤。

删除目标（精确匹配，仅测试痕迹）：
- memories：content 以 "verify_tools" 开头
- diaries ：content 以 "test diary" 开头 或 以 "verify_tools" 开头

用法：
  PYTHONPATH=backend .venv/Scripts/python scripts/cleanup_test_residue.py --dry-run   # 仅预览
  PYTHONPATH=backend .venv/Scripts/python scripts/cleanup_test_residue.py --yes       # 确认执行
  PYTHONPATH=backend .venv/Scripts/python scripts/cleanup_test_residue.py              # 交互式确认
"""
import argparse
import asyncio
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "backend"))
import aiosqlite
from database.core import DB_PATH


async def main(dry_run: bool = False, auto_yes: bool = False):
    conn = await aiosqlite.connect(str(DB_PATH))
    try:
        await conn.execute("PRAGMA journal_mode=WAL")

        cur = await conn.execute("SELECT id, category, content FROM memories")
        mems = await cur.fetchall()
        # 注意：不得匹配 E2E 固定夹具数据（「喜欢喝椰子水」），那是生产数据
        del_mem = [m for m in mems if (m[2] or "").startswith("verify_tools")]

        cur = await conn.execute("SELECT date, content FROM diaries")
        dias = await cur.fetchall()
        del_dia = [d for d in dias if (d[1] or "").startswith("test diary") or (d[1] or "").startswith("verify_tools")]

        if not del_mem and not del_dia:
            print("无测试残留，无需清理。")
            return

        print(f"\n待清理：记忆 {len(del_mem)} 条，日记 {len(del_dia)} 篇\n")
        for mid, cat, content in del_mem:
            print(f"  [记忆] [{mid[:8]}] cat={cat} | {content}")
        for date, content in del_dia:
            print(f"  [日记] {date} | {content}")

        if dry_run:
            print(f"\n[dry-run] 以上 {len(del_mem) + len(del_dia)} 条记录不会被实际删除。")
            return

        if not auto_yes:
            answer = input(f"\n确认删除以上 {len(del_mem) + len(del_dia)} 条记录？[y/N] ")
            if answer.lower() != 'y':
                print("已取消。")
                return

        for mid, cat, content in del_mem:
            await conn.execute("DELETE FROM memories WHERE id = ?", (mid,))

        for date, content in del_dia:
            await conn.execute("DELETE FROM diaries WHERE date = ?", (date,))

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
    parser = argparse.ArgumentParser(description="清理测试残留数据")
    parser.add_argument("--dry-run", action="store_true", help="仅预览，不实际删除")
    parser.add_argument("--yes", "-y", action="store_true", help="跳过确认提示，直接执行")
    args = parser.parse_args()
    asyncio.run(main(dry_run=args.dry_run, auto_yes=args.yes))
