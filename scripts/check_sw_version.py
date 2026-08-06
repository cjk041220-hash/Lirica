"""SW 版本发版检查：sw.js 内容改了但 CACHE_NAME 没 bump → 报错。

背景（2026-08-06 体检工程优化 3）：改 sw.js 本身（缓存策略 / 预缓存
清单 / 推送逻辑）时，CACHE_NAME 需要手动 bump（v6→v7），否则老浏览器
字节对比发现新 sw.js 后会正常更新，但 activate 不清旧缓存——新旧文件
混在同一个 cache 里，旧策略失效、预缓存清单不生效。漏 bump 不报错，
只会在「下次手动 bump」时连带清掉，问题静默潜伏。

检查逻辑：git diff HEAD 与工作区（含暂存区）的 sw.js——
- 没有改动 → 通过；
- 有非 CACHE_NAME 的改动行，且 CACHE_NAME 行没有跟着变 → 报错退出 1。

用法（发版前 / CI）：
    python scripts/check_sw_version.py
"""
import subprocess
import sys

SW_PATH = "web/public/sw.js"
CACHE_NAME_RE = "const CACHE_NAME"

_HEADER = """[SW 版本检查] %s"""


def _diff_lines() -> list[str]:
    """git diff HEAD -- sw.js 的增删行（不含 +++/--- 文件头）。"""
    proc = subprocess.run(
        ["git", "diff", "HEAD", "--", SW_PATH],
        capture_output=True, text=True, encoding="utf-8", errors="replace",
    )
    if proc.returncode != 0:
        print(_HEADER % "git diff 失败，跳过（非 git 环境）")
        return []
    return [
        line for line in proc.stdout.splitlines()
        if line.startswith("+") or line.startswith("-")
        if not line.startswith(("+++", "---"))
    ]


def main() -> int:
    changes = _diff_lines()
    if not changes:
        print(_HEADER % f"{SW_PATH} 无改动，通过")
        return 0

    cache_name_changed = any(CACHE_NAME_RE in line for line in changes)
    if cache_name_changed:
        print(_HEADER % f"{SW_PATH} 已改动且 CACHE_NAME 已同步 bump，通过")
        return 0

    print(_HEADER % f"【失败】{SW_PATH} 有 {len(changes)} 行改动，但 CACHE_NAME 没变")
    print("  改缓存策略/预缓存清单时请同步 bump CACHE_NAME（如 v7 → v8），")
    print("  否则旧缓存不退役，新策略不生效。")
    for line in changes[:12]:
        print(f"    {line[:100]}")
    if len(changes) > 12:
        print(f"    …（共 {len(changes)} 行）")
    return 1


if __name__ == "__main__":
    sys.exit(main())
