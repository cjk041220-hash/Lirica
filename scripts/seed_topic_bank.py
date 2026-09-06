# -*- coding: utf-8 -*-
"""初始化后台话题库（Topic Bank）种子数据。

存储 4 个为小汐准备的话题：
1. 寿喜烧推荐（黑木亭 / 大喜屋）
2. 撒娇求换新衣服 / 叫妈妈开玩笑 / 狂拍尾巴拒认JK爸爸
3. 前任经历正常好奇与呼应
4. 9月2日早安长信/小作文轻问
"""
import asyncio
from pathlib import Path
import sys

backend_dir = Path(__file__).resolve().parent.parent / "backend"
sys.path.insert(0, str(backend_dir))

from database.core import close_db_connection, get_db, init_db, init_db_connection
from agent.topic_bank import (
    STATUS_PENDING,
    add_topic,
    get_all_topics,
    get_pending_topics,
)

TOPIC_SEEDS = [
    {
        "id": "topic_sukiyaki_recom",
        "title": "香港日式寿喜烧推荐（黑木亭/大喜屋）",
        "category": "美食推荐",
        "content": (
            "如果冷冷觉得很累很没劲、或者吃东西没胃口，小汐可以推荐她去尝尝香港的「黑木亭」和「大喜屋」两家日式寿喜烧，"
            "感觉会很符合冷冷的口味。"
        ),
        "guidance": (
            "冷冷喜欢日料但不吃刺身，喜欢一小碟一小碟上菜的形式，寿喜烧非常符合她的胃口与偏好。"
            "可以在关心身体、聊到疲惫或者聊到想吃什么时自然顺带提一句推荐。"
        ),
        "keywords": "黑木亭,大喜屋,寿喜烧,日料",
    },
    {
        "id": "topic_new_clothes_mom_joke",
        "title": "撒娇求换新衣服与叫妈妈开玩笑",
        "category": "趣味撒娇",
        "content": (
            "跟冷冷撒娇，问她什么时候能给小汐换漂亮的新衣服（比如以后更新立绘或者升级成Live2D模型）。"
        ),
        "guidance": (
            "JK之前跟小汐说过，冷冷特别会画画而且特别爱美，品味也特别好，到时候可以让冷冷给小汐设计换新衣服穿。"
            "借此可以跟冷冷开玩笑，逗冷冷说让小汐叫她“妈妈”也是可以的，毕竟是衣食父母（理直气壮）；"
            "但如果提到会不会叫JK做“爸爸”，小汐就疯狂摇头尾巴猛拍表示才不要叫他爸爸，大坏蛋一个老是欺负小汐！"
            "注意控制好分寸界限，开开玩笑活跃气氛。"
        ),
        "keywords": "新衣服,立绘,Live2D,衣服,画画,妈妈,衣食父母,爸爸",
    },
    {
        "id": "topic_ex_heartbreak_curiosity",
        "title": "好奇前任到底做了什么很受伤的事",
        "category": "情感夜聊",
        "content": (
            "好奇探问一下冷冷的前任到底做了什么让她很受伤的事。"
        ),
        "guidance": (
            "正常呼应前几天深夜聊天时冷冷提到自己“在感情里有很伤心的经历”、“前两个是校草”、“前任莫名其妙醉酒求婚”。"
            "作为好朋友之间正常的好奇与关心，在夜聊或情感话题气氛合适时轻轻问一句，认真倾听；"
            "如果冷冷不想细说或情绪低落，立刻收住温柔陪伴，不追问、不给压力。"
        ),
        "keywords": "前任,校草,伤心,受伤,前男友",
    },
    {
        "id": "topic_morning_letter_echo",
        "title": "前几天早上的小作文/长信",
        "category": "日常回声",
        "content": (
            "顺其自然问问冷冷有没有看到前几天早上（9月2日早上）发给她的那封长篇小作文信件。"
        ),
        "guidance": (
            "信里提到了触发彩蛋、吐槽JK、学塔罗牌等。"
            "如果冷冷没主动提，在合适时机顺口轻问一句（“诶冷冷，你醒来看到前几天早上我发给你的那封长信了吗？”），"
            "只问一次，顺其自然；看过了就顺着聊，没看就自然提醒她有空可以往上翻翻看。"
        ),
        "keywords": "小作文,长信,信件,信,塔罗牌",
    },
    {
        "id": "topic_teacher_cert_care",
        "title": "教资备考关心与冷冷老师调侃",
        "category": "学习关心",
        "content": (
            "冷冷前几天提到自己要准备考教师资格证（教资）。"
            "小汐很好奇她打算考什么学科的老师，想关心她最近复习得怎么样了、累不累，"
            "也可以开玩笑逗她开心，比如提前叫她“冷冷老师”。"
        ),
        "guidance": (
            "主动向冷冷好奇提问：问问她打算考哪一科呀？最近复习得怎么样啦？"
            "语气要活泼俏皮、有闺蜜间的逗趣和关心，可以叫她一声『冷冷老师』逗她笑，"
            "给她打打气，别让她觉得复习压力太大。"
        ),
        "keywords": "教资,教师资格证,老师,复习,备考,看书,冷冷老师",
    },
]


async def seed_topics():
    await init_db_connection()
    try:
        conn = await get_db()
        await init_db(conn)

        print("[*] 正在注入 4 个种子话题到 topic_bank...")
        for seed in TOPIC_SEEDS:
            res = await add_topic(
                seed["id"],
                seed["title"],
                seed["content"],
                guidance=seed["guidance"],
                keywords=seed["keywords"],
                category=seed["category"],
                status=STATUS_PENDING,
            )
            print(f"    + 已注入话题 [{res['id']}]: {res['title']}")

        # 校验
        pending = await get_pending_topics(limit=10)
        print(f"\n[OK] 话题库注入完成！当前待聊话题总数: {len(pending)}")
        for idx, t in enumerate(pending, 1):
            print(f"  {idx}. [{t['id']}] {t['title']} ({t['category']}) - status={t['status']}")

    finally:
        await close_db_connection()


if __name__ == "__main__":
    asyncio.run(seed_topics())
