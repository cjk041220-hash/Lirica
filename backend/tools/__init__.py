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
from tools.events import create_event, list_events, update_event, delete_event
from tools.cycle import (
    log_period, amend_period,
    set_prompt_refresh_callback as set_cycle_prompt_refresh_callback,
)


def reset_tool_globals() -> None:
    """重置工具模块的进程级全局注入（测试隔离用）。

    工具依赖 set_memory_manager / set_diary_db_conn / set_http_client
    注入的模块级全局（P2-1 已知耦合；远期改为请求级 DI）。
    在此之前，测试通过 autouse fixture 在每个用例后调用本函数，
    防止跨用例串扰。
    """
    import tools.memory as _memory
    import tools.diary as _diary
    import tools.map_tools as _map
    import tools.cycle as _cycle

    _memory._memory_manager = None
    _diary._db_conn = None
    _map._shared_client = None
    _cycle._prompt_refresh_callback = None


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
        create_event,
        list_events,
        update_event,
        delete_event,
        log_period,
        amend_period,
    ]
