"""全局配置，从 .env 加载。"""
from pathlib import Path

from pydantic_settings import BaseSettings

# config/ 目录的绝对路径，确保任何 CWD 下都能找到 .env
_CONFIG_DIR = Path(__file__).resolve().parent


class Settings(BaseSettings):
    # DeepSeek
    deepseek_api_key: str = ""
    deepseek_base_url: str = "https://api.deepseek.com/v1"
    deepseek_model: str = "deepseek-v4.1-flash"

    # 高德地图（唯一地图引擎）
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

    # 本地启动的绑定地址/端口（main.py 消费；Docker 走 CLI uvicorn
    # --host/--port，不经此配置）。pydantic 自动认 env XIAOXI_BIND_HOST /
    # XIAOXI_PORT，写在 .env 里同样生效。注意：utils/security.py 的
    # detect_bind_host（启动安检）与本值保持同一数据源，缺了 .env 这层
    # 会出现「安检以为回环、实际绑 0.0.0.0」的 fail-open（体检 S9）
    xiaoxi_bind_host: str = "127.0.0.1"
    xiaoxi_port: int = 8000

    # 备份时剔除高敏数据（生理期记录 cycle_records、由其派生的周期
    # 推算事件 events.source='cycle'、对话档案 chat_sessions/chat_messages）。
    # 开启后每份备份生成时会清空这些行——对话档案是全应用最大体量的
    # 亲密数据，适合会把备份同步到网盘/他人的场景。代价是无法用备份
    # 恢复这些数据。默认 False（完整备份）。
    backup_scrub_sensitive: bool = False

    # Web Push (VAPID 密钥，用于向 PWA 客户端推送通知)
    vapid_public_key: str = ""
    vapid_private_key: str = ""
    # VAPID subject：必须是真实公网域名（mailto: 或 https:）。Apple 会拒绝
    # localhost / mDNS (.local) 等非公网域名——2026-08-15 实锤：
    # mailto:xiaoxi@yinghai.local → 恒 BadJwtToken，改真实域名 → 201。
    vapid_claim_mailto: str = "mailto:xiaoxi@selena0603.xyz"

    # presence 离线判定阈值（分钟，2026-08-09 用户裁定「关页面即离线 +
    # 45 分钟兜底」）：前端 pagehide 上报 leaving 立即可判定离线；手机
    # 杀进程 / 页面静默等没有信号源的场景，按距最后一次在线上报超过
    # 该分钟数判定离线。离线期间的第一条主动消息会新开 era 会话
    # （小汐的独处时间线窗口）。
    presence_timeout_minutes: int = 45

    # CORS 允许的前端来源（逗号分隔，开发默认 localhost:3000）
    cors_origins: str = "http://localhost:3000"

    # TOKEN_COST_WARNING（P2-1）：本地 token 预算告警阈值，单位 = 近似输入
    # token/日（cl100k 计数，对 DeepSeek 偏高估 1.3–2×，正是预算监控要的
    # 安全余量）。0 = 关闭（默认）。只累计 chat agent 的输入（成本大头，
    # graph.py 每次模型调用含工具循环），后台任务不累计。越过阈值按倍数
    # 递增打 WARNING。折算花费请对照 DeepSeek 控制台当前单价——价格会变，
    # 代码里不写死。实现见 utils/token_budget.py。
    token_cost_warning: int = 0

    # 日志落盘/级别（utils/logger.py 消费）。必须作为 Settings 字段声明：
    # logger 读的是 os.environ，而 pydantic 对未声明的 .env 键只「extra: ignore」，
    # 不声明的话写在 .env 里的 LOG_FILE 会被静默吞掉（体检 9.13 发现）
    log_file: str = ""
    log_level: str = ""

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
