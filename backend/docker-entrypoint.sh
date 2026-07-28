#!/bin/sh
# 容器入口：启动 uvicorn 前确保存在 API Token，规避「--host 0.0.0.0 + 无 token」
# 触发 fail-closed 校验而崩溃循环。生成的 token 持久化到 data/.api-token，
# 重启时复用——否则每次重启换 token，浏览器里存的旧 token 会陷入 401 死循环。
TOKEN_FILE="/app/backend/data/.api-token"

if [ -z "${XIAOXI_API_TOKEN:-}" ]; then
  # 复用上次持久化的 token（重启不变）
  if [ -f "$TOKEN_FILE" ]; then
    XIAOXI_API_TOKEN="$(cat "$TOKEN_FILE" 2>/dev/null || true)"
  fi
  # 首次启动且未显式允许无认证：生成强随机 token 并持久化
  if [ -z "${XIAOXI_API_TOKEN:-}" ] && [ "${XIAOXI_ALLOW_INSECURE:-}" != "true" ]; then
    XIAOXI_API_TOKEN="$(python -c 'import secrets; print(secrets.token_hex(32))')"
    if ( umask 077; printf '%s\n' "$XIAOXI_API_TOKEN" > "$TOKEN_FILE" ) 2>/dev/null; then
      printf '\n==============================================================\n'
      printf ' 小汐：首次启动已自动生成 API Token（已持久化到 %s）\n' "$TOKEN_FILE"
      printf ' 请把下面这串令牌填进浏览器「设置 - 访问令牌」（仅显示这一次）：\n\n'
      printf '   %s\n\n' "$XIAOXI_API_TOKEN"
      printf ' 说明：.env 若已设 XIAOXI_API_TOKEN 则以其为准；纯本机开发可设\n'
      printf '       XIAOXI_ALLOW_INSECURE=true 跳过认证（切勿用于公网）。\n'
      printf '==============================================================\n\n'
    else
      printf '\n[entrypoint] 警告：无法写入 %s（目录权限？）。未提供 XIAOXI_API_TOKEN\n' "$TOKEN_FILE" >&2
      printf '[entrypoint] 时 fail-closed 校验将拒绝启动；请在 .env 显式设置该 token。\n\n' >&2
    fi
  fi
  export XIAOXI_API_TOKEN
fi

exec "$@"
