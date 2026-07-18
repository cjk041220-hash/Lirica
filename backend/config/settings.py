"""全局配置，从 .env 加载。"""
from pathlib import Path

from pydantic_settings import BaseSettings

# config/ 目录的绝对路径，确保任何 CWD 下都能找到 .env
_CONFIG_DIR = Path(__file__).resolve().parent


class Settings(BaseSettings):
    # DeepSeek
    deepseek_api_key: str = ""
    deepseek_base_url: str = "https://api.deepseek.com/v1"
    deepseek_model: str = "deepseek-chat"

    # 高德地图
    amap_api_key: str = ""

    # Tavily 网络搜索
    tavily_api_key: str = ""

    # 高德天气（如果未来使用高德天气 API）
    amap_weather_key: str = ""

    model_config = {
        "env_file": str(_CONFIG_DIR / ".env"),
        "env_file_encoding": "utf-8",
        "extra": "ignore",
    }


_settings: Settings | None = None


def get_settings() -> Settings:
    """获取全局 Settings 单例。"""
    global _settings
    if _settings is None:
        _settings = Settings()
    return _settings
