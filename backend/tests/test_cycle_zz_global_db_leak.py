"""回归：database.core._db 不得跨用例泄漏。

**文件名是故意的**——pytest 按文件名排序收集，本文件必须排在
test_cycle_tools.py / test_cycle_tracker.py 之后（"cycle_zz" > "cycle_t"），
才能观察到那两个文件跑完之后留下的全局状态。重命名会让它失去意义
（不会误报，但会变成空转）。

背景：那两个文件的每个用例开头都要把 `database.core._db` 接到 tmp_db，
好让 utils.cycle_tracker / utils.events 里 `from database.core import
get_db` 的引用一并走临时库，省掉 mock。但原来写作**直接赋值、从不还原**
（共 36 处），跑完之后 `_db` 永久指向一个失效连接；此后任何调用
get_db() 又不自己打补丁的用例都会拿到死连接，报
`ValueError: no active connection`。2026-08-30 已全部改为
`monkeypatch.setattr(db_core, "_db", tmp_db)`。

本用例就是给这个修法上的锁——哪天又有人改回直接赋值，这里立刻红。

同类问题在 test_integration.py 上也炸过一次：integration_app 夹具手动
保存/还原六个模块的 get_db，与用例体内的 monkeypatch 收尾顺序打架
（finalizer 逆序 → 夹具先还原成真身、monkeypatch.undo() 再把 mock
装回去），同样已改为 monkeypatch。
"""
from database import core


async def test_global_db_restored_after_cycle_tests():
    """cycle_* 跑完后，_db 应被 monkeypatch 还原回 None。"""
    assert core._db is None, (
        f"database.core._db 泄漏，仍指向 {core._db!r}——"
        "test_cycle_tools / test_cycle_tracker 又用了直接赋值而非 monkeypatch？"
    )
