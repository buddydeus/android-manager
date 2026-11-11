#!/usr/bin/env bash
set -euo pipefail
umask 077
cd -- "$(dirname -- "${BASH_SOURCE[0]}")"
fail() { printf '%s\n' "$*" >&2; exit 1; }
command -v docker >/dev/null || fail '请先安装Docker Engine及Compose插件，再重新运行。'
docker compose version >/dev/null
docker info >/dev/null
# Fixed project name matches the previous documented server/ deployment volume namespace.
compose() { docker compose --project-name server --env-file .env -f compose.yaml "$@"; }
if [[ ! -e .env && ! -L .env ]]; then
  key=${AMS_ENROLLMENT_KEY:-}
  if [[ -z "$key" && -t 0 ]]; then read -r -s -p '请输入与App构建一致的接入凭据: ' key; printf '\n'; fi
  [[ "$key" =~ ^[A-Za-z0-9_-]+$ && ${#key} -ge 32 && ${#key} -le 256 ]] || fail '首次部署需提供32至256位字母、数字、下划线或短横线接入凭据（AMS_ENROLLMENT_KEY）。'
  (set -o noclobber; printf 'AMS_ENROLLMENT_KEY=%s\n' "$key" > .env)
  unset key
fi
[[ -f .env && ! -L .env ]] || fail '.env必须为普通文件，不能是链接。'
chmod 600 .env
# The saved credential is authoritative on retries, even if the caller exports another key.
unset AMS_ENROLLMENT_KEY
# Never source .env as shell code. Compose parses it and validates required variables.
compose config --quiet
compose build ams
state=$(compose run --rm --no-deps -T ams node dist/api/src/deployment-cli.js)
case "$state" in
  initialized) printf '保留已有TOTP管理员账号。\n' ;;
  pending) printf '待确认TOTP绑定，保留已有绑定资料；请运行admin-cli.js confirm。\n' ;;
  legacy) fail '旧密码账号需先用服务器命令完成TOTP绑定迁移；不会自动替换身份。' ;;
  uninitialized)
    AMS_ADMIN_USER=${AMS_ADMIN_USER:-}
    if [[ -t 0 && -z "$AMS_ADMIN_USER" ]]; then read -r -p '管理员账号: ' AMS_ADMIN_USER; fi
    [[ -n "$AMS_ADMIN_USER" ]] || fail '首次部署需提供管理员账号（AMS_ADMIN_USER），不使用固定密码。'
    export AMS_ADMIN_USER
    compose run --rm --no-deps -T -e AMS_ADMIN_USER ams node dist/api/src/admin-cli.js init
    unset AMS_ADMIN_USER
    printf '请读取数据卷中的私有totp-enrollment.json添加身份验证器，然后运行admin-cli.js confirm启用登录。\n'
    ;;
  *) fail '部署状态异常，停止；不会清除数据库。' ;;
esac
compose up -d --wait --wait-timeout 180
printf '容器已启动并通过健康检查。访问 https://ams.opt.work；公网DNS和HTTPS签发需另外确认。\n'
