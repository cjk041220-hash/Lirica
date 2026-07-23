"""全局配置，从 .env 加载。"""
from pathlib import Path

from pydantic_settings import BaseSettings

# config/ 目录的绝对路径，确保任何 CWD 下都能找到 .env
_CONFIG_DIR = Path(__file__).resolve().parent


class Settings(BaseSettings):
    # DeepSeek
    deepseek_api_key: str = ""
    deepseek_base_url: str = "https://api.deepseek.com/v1"
    deepseek_model: str = "deepseek-v4-flash"

    # 高德地图
    amap_api_key: str = ""

    # Tavily 网络搜索
    tavily_api_key: str = ""

    # API 认证 Token（留空则不启用认证；部署到公网时务必设置）
    xiaoxi_api_token: str = ""

    # 是否信任反向代理的 X-Forwarded-For（限流 IP 归因用）。
    # 仅在明确部署于可信代理（Nginx/Caddy 等）之后时才开启；
    # 直连部署时开启会让攻击者伪造 XFF 绕过限流。
    trust_proxy: bool = False

    # 显式承担"无 token 绑定非回环地址"的风险（仅限受控内网）。
    # 默认 False：非回环绑定 + 空 token 直接拒绝启动（fail closed）。
    xiaoxi_allow_insecure: bool = False

    # 备份时剔除高敏数据（生理期记录 cycle_records）。
    # 开启后每份备份生成时会清空该表——备份不再含生理期数据，
    # 代价是无法用备份恢复该表。默认 False（完整备份）。
    backup_scrub_sensitive: bool = False

    # Web Push (VAPID 密钥，用于向 PWA 客户端推送通知)
    vapid_public_key: str = ""
    vapid_private_key: str = ""
    vapid_claim_mailto: str = "mailto:xiaoxi@yinghai.local"

    # CORS 允许的前端来源（逗号分隔，开发默认 localhost:3000）
    cors_origins: str = "http://localhost:3000"

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
