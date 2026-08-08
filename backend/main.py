"""小汐 — 启动入口。"""
import sys
import traceback

from version import __version__


def _p(text: str):
    """安全打印（避免 emoji 在 GBK 终端崩溃）。"""
    try:
        print(text)
    except UnicodeEncodeError:
        print(text.encode("ascii", errors="replace").decode("ascii"))


def main():
    _p(f"[xixi] v{__version__} — 映海正在泛起涟漪……")
    _p("")

    try:
        import uvicorn

        from config.settings import get_settings
        s = get_settings()
        uvicorn.run(
            "api.server:app",
            host=s.xiaoxi_bind_host,
            port=s.xiaoxi_port,
        )
    except Exception:
        _p("=" * 50)
        _p("[xixi] 启动失败！错误信息：")
        traceback.print_exc(file=sys.stderr)
        _p("=" * 50)
        _p("")
        _p("常见原因：")
        _p("  1. config\\.env 文件中的 API Key 未配置或无效")
        _p("  2. 依赖包缺失 — 请运行: .venv\\Scripts\\pip.exe install -r requirements-dev.txt")
        _p("  3. 端口被占用 — 请关闭占用程序后重试，或在 config\\.env 调整 XIAOXI_PORT")
        _p("")
        try:
            input("按 Enter 退出……")
        except EOFError:
            pass  # stdin 已关闭（管道/无终端场景）时 input 会再抛 EOFError，直接退出即可
        sys.exit(1)


if __name__ == "__main__":
    main()
