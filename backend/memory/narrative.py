"""叙事构建器 — 将离散记忆编织为连贯叙事，注入系统提示词。

v2 重构：
- 适配新 schema 字段名（created_at 替代 time）
- 删除无用的 set_manager() 方法
- 简化输出格式
"""
from memory.manager import MemoryManager

# 单条记忆注入长度上限（字符）：
# - 控制注入 token 预算（max_items=5 时总量可控）
# - 收敛 prompt injection 的攻击面（纵深防御第二层）
_MAX_ITEM_LEN = 200


class NarrativeBuilder:
    """将记忆列表构建为适合注入系统提示词的叙述文本。

    不做 LLM 二次总结（保持简洁），而是按类别分组 + 时间排序，
    形成自然的人物记忆画像。

    注意：构建结果由 pre_model_hook 注入 llm_input_messages，
    不会进入 LangGraph checkpoint，避免上下文污染。
    """

    def __init__(self, manager: MemoryManager | None = None):
        self._manager = manager

    async def build(self, max_items: int = 30, query: str = "") -> str:
        """从 MemoryManager 读取记忆，构建叙事文本。

        检索策略为 hybrid（修复 P2-2）：
        - top-K 相关性（recall：时间衰减 × 访问热度 × 话题命中）——让
          "重要但较旧"的记忆（如核心偏好、重要约定）也能进入系统侧叙事，
          而不是只有最近几天的流水账被"自然想起"；
        - 其余配额给最新记忆（recent）——保证"刚发生的事"在场。
        两路去重后不足 max_items 时用 recent 补足，最终按时间正序渲染。

        query 传当前对话的最新一句话时，相关路按话题重叠加分——
        聊天气时和聊心事时，浮现的不再是同一批固定阵容。
        """
        if self._manager is None:
            return ""

        # 画像优先注入（批次②）：固定 id 的「小汐眼里的冷冷」块单独
        # 渲染在最前——它是蒸馏过的动态认识，比单条记忆更值得先看。
        # 常规召回已排除该行（PROFILE_ROW_FILTER），这里不会重复。
        # 查询必须放在 total==0 门卫之前（审查 I-1）：count() 同样
        # 排除该行，画像独存时 total==0 会提前返回——先取画像才能在
        # 该场景保住画像段。
        profile_text = ""
        cursor = await self._manager._db.execute(
            "SELECT content FROM memories WHERE id = ?",
            ("profile_cold",),
        )
        prow = await cursor.fetchone()
        if prow and prow[0]:
            # 与常规叙事同纪律：压平换行（防伪造结构）+ 截断超长
            content = " ".join(prow[0].split())
            if len(content) > _MAX_ITEM_LEN:
                content = content[:_MAX_ITEM_LEN] + "……"
            profile_text = f"## 小汐眼里的冷冷\n- {content}"

        total = await self._manager.count()
        if total == 0:
            # 仅画像独存（常规记忆为空）：把画像段带回去，不整段丢弃；
            # 无画像且无记忆时为 ""（空库原语义）。
            return profile_text

        # 情绪联动（schema v18）：查一次当前心情效价，传给 recall 的
        # mood-congruent 因子。未知 mood 词 / 无 life_state 行 → None，
        # 因子自动关闭，行为退回 v17（降级路径有测试钉死）。
        mood_valence = await self._manager.get_mood_valence()

        # passive=True：叙事注入是系统被动读取，不能计入访问热度。
        # 否则会形成正反馈闭环——top 记忆每次注入都被刷新 last_used_at /
        # access_count，永远霸占相关性配额，新记忆挤不进来。
        relevance_k = min(3, max_items)
        relevant = await self._manager.recall(
            limit=relevance_k, passive=True, query=query,
            mood_valence=mood_valence,
        )
        recent = await self._manager.recent(max_items)

        picked: list[dict] = []
        seen: set[str] = set()
        for m in relevant:  # 相关性在前：重要/热门记忆优先浮现
            if m["id"] not in seen:
                seen.add(m["id"])
                picked.append(m)
        for m in reversed(recent):  # 最新的在前，补足配额
            if len(picked) >= max_items:
                break
            if m["id"] not in seen:
                seen.add(m["id"])
                picked.append(m)

        # core 保底席位（B2）：相关路 + 最新路全被近期流水占满时，
        # 至少保证一条 core 记忆（高频复用的画像基石）在场——替换
        # 末位（recent 补足席），损失最小；相关路自身常已含 core。
        if picked and not any(m.get("status") == "core" for m in picked):
            cores = await self._manager.top_core(limit=1)
            if cores and cores[0]["id"] not in seen:
                picked[-1] = cores[0]

        # 渲染按时间正序（与 recent() 原语义一致）
        memories = sorted(picked, key=lambda m: m.get("created_at") or "")

        if not memories:
            # 常规记忆为空但画像存在：仍把画像段带回去，不整段丢弃。
            # （无画像时 profile_text 为 ""，两种情况合一行。）
            return profile_text

        # 按类别分组（memories 已由 recent() 按时间正序排列）
        # 每条内容压平换行（防止伪造叙事结构）并截断超长内容
        grouped: dict[str, list[str]] = {}
        for m in memories:
            cat = m.get("category", "其他")
            content = " ".join(m.get("content", "").split())
            if len(content) > _MAX_ITEM_LEN:
                content = content[:_MAX_ITEM_LEN] + "……"
            if cat not in grouped:
                grouped[cat] = []
            grouped[cat].append(content)

        lines: list[str] = []
        if profile_text:
            lines.append(profile_text)
            lines.append("")
        lines.append("以下是小汐记住的关于冷冷的事情：")
        lines.append("")
        for cat, items in grouped.items():
            lines.append(f"### {cat}")
            for item in items:
                lines.append(f"- {item}")
            lines.append("")

        return "\n".join(lines)
