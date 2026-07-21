"""小汐的工具箱 — 所有可用工具的统一注册。"""
from tools.weather import get_weather
from tools.map_tools import (
    search_nearby,
    geocode_address,
    fuzzy_search_place,
    get_transit_route,
    get_cycling_route,
    set_http_client,
)
from tools.search import web_search
from tools.memory import (
    remember, recall, list_memories,
    delete_memory, export_memories, update_memory,
    set_memory_manager,
)
from tools.diary import write_diary, read_diary, set_db_conn as set_diary_db_conn
from tools.tarot import tarot


def get_all_tools() -> list:
    """获取小汐的全部可用工具。

    在应用启动时调用，返回列表传给 build_agent()。
    """
    return [
        get_weather,
        search_nearby,
        geocode_address,
        fuzzy_search_place,
        get_transit_route,
        get_cycling_route,
        web_search,
        remember,
        recall,
        update_memory,
        list_memories,
        delete_memory,
        export_memories,
        write_diary,
        read_diary,
        tarot,
    ]
