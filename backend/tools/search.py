"""网络搜索工具 — Tavily。"""
from langchain_core.tools import tool
from config.settings import get_settings


@tool
def web_search(query: str) -> str:
    """在网络上搜索资讯。小汐会去映海里帮冷冷找人类世界的信息。

    Args:
        query: 搜索关键词，如「香港近期活动」
    """
    try:
        from tavily import TavilyClient

        s = get_settings()
        if not s.tavily_api_key:
            return "小汐的映海搜索功能还没有开启……冷冷需要先配置 TAVILY_API_KEY 哦。"
        client = TavilyClient(api_key=s.tavily_api_key)
        result = client.search(query, search_depth="basic", max_results=5)

        if not result.get("results"):
            return "小汐去海里找了一圈，但是没有找到相关的信息……"

        response = f"🐋 小汐去映海里找到这些关于「{query}」的东西：\n\n"
        for i, r in enumerate(result["results"][:5], 1):
            response += f"{i}. **{r.get('title', '?')}**\n"
            response += f"   {r.get('content', '')[:150]}……\n"
            if r.get("url"):
                response += f"   🔗 {r['url']}\n"
            response += "\n"

        return response
    except Exception as e:
        return f"今天映海的水有点混浊，小汐看不到远处……（{str(e)[:100]}）"
