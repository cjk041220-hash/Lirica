# -*- coding: utf-8 -*-
"""一键拉取生产服务器数据库到本地进行冷备份与完整性核对。

使用方式：
    python scripts/pull_server_backup.py
"""
import datetime
import os
import subprocess
import sys
from pathlib import Path

def main():
    now_str = datetime.datetime.now().strftime("%Y%m%d_%H%M%S")
    local_backup_dir = Path(__file__).resolve().parent.parent / "backend" / "data" / "backups"
    local_backup_dir.mkdir(parents=True, exist_ok=True)
    target_file = local_backup_dir / f"server_snapshot_{now_str}.db"

    print(f"[*] 开始从生产服务器拉取热备快照到: {target_file}")

    # 1. 在服务器上通过 Online Backup API 创建原子快照
    create_snap_cmd = [
        "ssh", "xiaoxi",
        "docker exec xiaoxi python -c \""
        "import sqlite3; "
        "src = sqlite3.connect('/app/backend/data/userdata.db'); "
        "dst = sqlite3.connect('/tmp/snapshot_export.db'); "
        "src.backup(dst); "
        "dst.close(); src.close(); "
        "print('SNAPSHOT_OK')\""
    ]
    res1 = subprocess.run(create_snap_cmd, capture_output=True, text=True)
    if "SNAPSHOT_OK" not in res1.stdout:
        print(f"[!] 服务器创建快照失败: {res1.stderr}")
        return

    # 2. 从 docker 拷出并 scp 到本地
    scp_cmd = [
        "ssh", "xiaoxi",
        "docker cp xiaoxi:/tmp/snapshot_export.db /tmp/snapshot_export.db"
    ]
    subprocess.run(scp_cmd, check=True)

    download_cmd = [
        "scp",
        "xiaoxi:/tmp/snapshot_export.db",
        str(target_file)
    ]
    res2 = subprocess.run(download_cmd, capture_output=True)
    if res2.returncode != 0:
        print(f"[!] 下载快照失败: {res2.stderr.decode('utf-8', errors='ignore')}")
        return

    # 3. 清理服务器临时文件
    subprocess.run(["ssh", "xiaoxi", "rm -f /tmp/snapshot_export.db"], capture_output=True)

    # 4. 本地完整性校验
    import sqlite3
    conn = sqlite3.connect(str(target_file))
    cur = conn.cursor()
    sessions = cur.execute("SELECT count(*) FROM chat_sessions").fetchone()[0]
    messages = cur.execute("SELECT count(*) FROM chat_messages").fetchone()[0]
    memories = cur.execute("SELECT count(*) FROM memories WHERE status IN ('active', 'core')").fetchone()[0]
    diaries = cur.execute("SELECT count(*) FROM diaries").fetchone()[0]
    events = cur.execute("SELECT count(*) FROM events WHERE status='active'").fetchone()[0]
    conn.close()

    print(f"\n[OK] 备份成功！文件大小: {os.path.getsize(target_file)} 字节")
    print(f"    - 对话会话: {sessions} 个")
    print(f"    - 对话消息: {messages} 条")
    print(f"    - 核心记忆: {memories} 条")
    print(f"    - 日记记录: {diaries} 篇")
    print(f"    - 活跃事件: {events} 条")

if __name__ == "__main__":
    main()
