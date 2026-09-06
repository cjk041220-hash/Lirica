"""小汐后台话题库管理器（Topic Bank）。

设计目标：
1. 话题库仅在后台管理与存储，完全对前端界面隐藏；
2. 「聊到一个删一个」：当某个话题在对话中被聊到后，该话题从待聊池核销（status: consumed），
   未聊到的话题持久保留供后续寻找话头；
3. 记忆与话题库严格解耦：核销话题库仅删除该话题素材种子，绝对不触碰与影响小汐已记住的
   真实记忆（memories 表）与历史记录（chat_messages 表）；
4. 提示词注入遵循高情商、自然交流纪律：仅在闲聊或话题自然转换时适时挑选一个切入，
   严禁任务清单式排队盘问，冷冷倾诉或情绪波动时无条件优先陪伴接住情绪。
"""
import datetime
import uuid
from typing import Any

import aiosqlite

from database.core import get_db, rows_to_dicts
from utils.logger import get_logger
from utils.timezone import HKT

log = get_logger(__name__)

# 状态枚举
STATUS_PENDING = "pending"
STATUS_CONSUMED = "consumed"
STATUS_ARCHIVED = "archived"


async def add_topic(
    topic_id: str,
    title: str,
    content: str,
    *,
    guidance: str = "",
    keywords: str = "",
    category: str = "chat",
    status: str = STATUS_PENDING,
) -> dict[str, Any]:
    """添加或更新话题到话题库。"""
    conn = await get_db()
    now = datetime.datetime.now(HKT).strftime("%Y-%m-%d %H:%M")

    await conn.execute(
        """INSERT INTO topic_bank (id, title, category, content, guidance, keywords, status, created_at)
           VALUES (?, ?, ?, ?, ?, ?, ?, ?)
           ON CONFLICT(id) DO UPDATE SET
               title = excluded.title,
               category = excluded.category,
               content = excluded.content,
               guidance = excluded.guidance,
               keywords = excluded.keywords,
               status = excluded.status""",
        (topic_id, title, category, content, guidance, keywords, status, now),
    )
    await conn.commit()
    log.info("话题库添加/更新话题 [%s]: %s (status=%s)", topic_id, title, status)
    return {
        "id": topic_id,
        "title": title,
        "category": category,
        "content": content,
        "guidance": guidance,
        "keywords": keywords,
        "status": status,
        "created_at": now,
    }


async def get_pending_topics(limit: int = 5) -> list[dict[str, Any]]:
    """获取待聊话题列表（status = 'pending'）。"""
    conn = await get_db()
    cursor = await conn.execute(
        """SELECT id, title, category, content, guidance, keywords, status, created_at, consumed_at
           FROM topic_bank
           WHERE status = 'pending'
           ORDER BY created_at ASC
           LIMIT ?""",
        (limit,),
    )
    rows = await cursor.fetchall()
    return rows_to_dicts(cursor, rows)


async def get_all_topics() -> list[dict[str, Any]]:
    """获取话题库全部话题（供后台管理和测试）。"""
    conn = await get_db()
    cursor = await conn.execute(
        """SELECT id, title, category, content, guidance, keywords, status, created_at, consumed_at
           FROM topic_bank
           ORDER BY created_at ASC"""
    )
    rows = await cursor.fetchall()
    return rows_to_dicts(cursor, rows)


async def consume_topic(topic_id: str) -> bool:
    """核销话题（标记为 consumed，记录 consumed_at）。

    注意：这仅从话题库中核销此待聊素材，不会删除任何真实记忆或聊天记录。
    """
    conn = await get_db()
    now = datetime.datetime.now(HKT).strftime("%Y-%m-%d %H:%M")
    cursor = await conn.execute(
        """UPDATE topic_bank
           SET status = 'consumed', consumed_at = ?
           WHERE id = ? AND status = 'pending'""",
        (now, topic_id),
    )
    await conn.commit()
    if cursor.rowcount > 0:
        log.info("话题已核销 (consumed) [%s]", topic_id)
        return True
    return False


async def delete_topic(topic_id: str) -> bool:
    """物理删除指定话题。"""
    conn = await get_db()
    cursor = await conn.execute(
        "DELETE FROM topic_bank WHERE id = ?",
        (topic_id,),
    )
    await conn.commit()
    if cursor.rowcount > 0:
        log.info("话题已物理删除 [%s]", topic_id)
        return True
    return False


def format_topic_prompt(topics: list[dict[str, Any]]) -> str:
    """将待聊话题列表格式化为小汐系统提示词的一个独立章节。"""
    if not topics:
        return ""

    lines = [
        "## 小汐想找机会聊的话题清单（话题库素材）",
        "这些是小汐心里一直想找合适时机和冷冷聊的事情。它们是小汐自己的心思和素材库，不是任务清单：",
        "- **极度克制，自然顺带**：只有在冷冷闲下来、主动开启闲聊、或者话题自然转换时，才可以挑出【其中一个】话题自然聊起；",
        "- **严禁清单式盘问**：绝对不要连续抛出多个话题，更不要像做任务一样逐一核对清单；",
        "- **冷冷感受永远第一**：如果冷冷正在倾诉情绪、吐槽或者身体疲惫，全力以赴倾听陪伴她，绝对不要生硬转移到这些话题；",
        "- **聊过即过**：某个话题聊起后，顺着当下的对话自然往下聊即可，不用刻意向冷冷报备。",
        "",
        "当前心里惦记的话题：",
    ]

    for idx, t in enumerate(topics, 1):
        lines.append(f"{idx}. 【{t['title']}】")
        lines.append(f"   - 话题内容：{t['content']}")
        if t.get("guidance"):
            lines.append(f"   - 聊法指引：{t['guidance']}")

    return "\n".join(lines)


async def auto_consume_topics_from_text(messages: list[str]) -> list[str]:
    """从对话文本中检测是否已涉及待聊话题，若命中则自动核销。

    检查依据：
    1. keywords 中的关键词（逗号分隔）在消息中出现；
    2. 或 title 中的核心词在消息中出现；
    3. 特殊复合条件匹配（如 寿喜烧/黑木亭/大喜屋、新衣服+立绘+妈妈、前任+受伤/校草、长信/小作文）。

    返回已核销的 topic_id 列表。
    """
    if not messages:
        return []

    combined_text = "\n".join(messages)
    pending = await get_pending_topics(limit=20)
    consumed_ids: list[str] = []

    for topic in pending:
        tid = topic["id"]
        keywords_raw = topic.get("keywords", "") or ""
        kw_list = [k.strip() for k in keywords_raw.split(",") if k.strip()]

        matched = False

        # 1. 明确关键词命中
        for kw in kw_list:
            if kw and kw in combined_text:
                matched = True
                break

        # 2. 专用话题复合规则
        if not matched:
            if tid == "topic_sukiyaki_recom":
                if any(w in combined_text for w in ("黑木亭", "大喜屋", "寿喜烧")):
                    matched = True
            elif tid == "topic_new_clothes_mom_joke":
                if ("新衣服" in combined_text or "Live2D" in combined_text or "换衣服" in combined_text) and (
                    "妈妈" in combined_text or "衣食父母" in combined_text or "立绘" in combined_text
                ):
                    matched = True
            elif tid == "topic_ex_heartbreak_curiosity":
                if ("前任" in combined_text or "前男友" in combined_text) and (
                    "受伤" in combined_text or "伤心" in combined_text or "做了什么" in combined_text or "校草" in combined_text
                ):
                    matched = True
            elif tid == "topic_morning_letter_echo":
                if ("长信" in combined_text or "小作文" in combined_text) or (
                    "信" in combined_text and ("今早" in combined_text or "早上" in combined_text or "前几天" in combined_text)
                ):
                    matched = True
            elif tid == "topic_teacher_cert_care":
                if any(w in combined_text for w in ("教资", "教师资格证", "冷冷老师", "考老师", "备考")):
                    matched = True

        if matched:
            success = await consume_topic(tid)
            if success:
                consumed_ids.append(tid)
                log.info("对话自动核销话题 [%s: %s]", tid, topic["title"])

    return consumed_ids
