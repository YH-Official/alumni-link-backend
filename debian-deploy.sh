#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────
# Debian 上把校友录后端跑起来的脚本。
#
# 一次性准备（在能联网的 Debian 上）：
#   sudo bash debian-deploy.sh install-docker
#
# 之后每次部署：
#   bash debian-deploy.sh load      # 导入 images/ 里的镜像（离线场景）
#   bash debian-deploy.sh up        # 启动
#   bash debian-deploy.sh verify    # 验收
#
# 空库首次：
#   bash debian-deploy.sh bootstrap # 建表（会 drop+create），完成后自动关掉 DDL
#
# 需要和本脚本放在同一个目录：docker-compose.yml、（可选）.env、images/*.tar.gz
# ─────────────────────────────────────────────────────────────
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$HERE"

APP_IMAGE="${ALUMNI_IMAGE:-alumni-link:1.0.0}"
IMAGE_DIR="$HERE/images"

log() { printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }
warn() { printf '\033[1;33m[注意]\033[0m %s\n' "$*" >&2; }
die() { printf '\n\033[1;31m[错误]\033[0m %s\n' "$*" >&2; exit 1; }

need_root() {
    [ "$(id -u)" -eq 0 ] || die "这一步需要 root：sudo bash $0 ${1:-}"
}

compose() {
    if docker compose version >/dev/null 2>&1; then
        docker compose "$@"
    else
        die "缺少 docker compose v2 插件。Debian 自带的 docker.io 不带它，
     请用 sudo bash $0 install-docker 安装 docker-ce（内含 compose 插件）。"
    fi
}

# 读取 .env 里的配置（没有就用默认值）
load_env() {
    if [ -f "$HERE/.env" ]; then
        # shellcheck disable=SC1091
        set -a; . "$HERE/.env"; set +a
    else
        if [ -f "$HERE/.env.example" ]; then
            cp "$HERE/.env.example" "$HERE/.env"
            # 默认密码是占位符，第一次跑时换成随机值
            if grep -q '改成你的强密码' "$HERE/.env"; then
                pw=$(head -c 32 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | head -c 24)
                sed -i "s|^DB_PASSWORD=.*|DB_PASSWORD=$pw|" "$HERE/.env"
                warn "已生成随机数据库密码，存在 .env 里，请自己备份：$pw"
            fi
            load_env
        else
            die "缺少 $HERE/.env 和 .env.example"
        fi
    fi
    DB_PASSWORD="${DB_PASSWORD:?数据库密码未设置}"
    APP_PORT="${APP_PORT:-7031}"
    DDL_BOOTSTRAP="${DDL_BOOTSTRAP:-false}"
    export DB_PASSWORD APP_PORT DDL_BOOTSTRAP ALUMNI_IMAGE="${ALUMNI_IMAGE:-$APP_IMAGE}"
}

# 等 db 容器报 health
wait_db() {
    log "等待数据库就绪"
    for _ in $(seq 1 60); do
        if compose ps --format '{{.Service}} {{.Health}}' 2>/dev/null | grep -q '^db healthy'; then
            echo "  数据库已就绪"
            return 0
        fi
        sleep 2
    done
    compose logs --tail 30 db || true
    die "数据库 120 秒内没有就绪"
}

# 等 app 容器报 health
wait_app() {
    log "等待应用启动"
    for _ in $(seq 1 60); do
        if compose ps --format '{{.Service}} {{.Health}}' 2>/dev/null | grep -q '^app healthy'; then
            echo "  应用已就绪"
            return 0
        fi
        sleep 2
    done
    compose logs --tail 40 app || true
    die "应用 120 秒内没有就绪"
}

db_sql() {
    compose exec -T db mariadb -uroot -p"$DB_PASSWORD" "$@"
}

table_count() {
    db_sql -N -e "SELECT COUNT(*) FROM information_schema.tables
                  WHERE table_schema='alumin-link' AND table_type='BASE TABLE';" 2>/dev/null | tr -d '\r '
}

cmd_install_docker() {
    need_root install-docker

    if command -v docker >/dev/null 2>&1 && docker compose version >/dev/null 2>&1; then
        log "docker 和 compose v2 都已安装"
        docker --version
        docker compose version
        return 0
    fi

    [ -r /etc/os-release ] || die "读不到 /etc/os-release"
    # shellcheck disable=SC1091
    . /etc/os-release
    case "${ID}${ID_LIKE:-}" in
        *debian*) ;;
        *) die "这个脚本只处理 Debian，当前系统是 ${PRETTY_NAME:-未知}" ;;
    esac

    log "安装基础依赖"
    apt-get update
    DEBIAN_FRONTEND=noninteractive apt-get install -y ca-certificates curl gnupg

    # 国内网络把官方源换成阿里云：DOCKER_APT_MIRROR=https://mirrors.aliyun.com/docker-ce
    local base="${DOCKER_APT_MIRROR:-https://download.docker.com}"
    log "添加 Docker 软件源：$base"
    install -m 0755 -d /etc/apt/keyrings
    if ! curl -fsSL --max-time 30 "$base/linux/debian/gpg" -o /etc/apt/keyrings/docker.asc; then
        die "下载 GPG 公钥失败。国内网络请改用：
     sudo DOCKER_APT_MIRROR=https://mirrors.aliyun.com/docker-ce bash $0 install-docker"
    fi
    chmod a+r /etc/apt/keyrings/docker.asc

    echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] $base/linux/debian ${VERSION_CODENAME} stable" \
        > /etc/apt/sources.list.d/docker.list

    log "安装 docker-ce"
    apt-get update
    DEBIAN_FRONTEND=noninteractive apt-get install -y \
        docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin

    systemctl enable --now docker
    docker --version
    docker compose version
    log "安装完成。普通用户想免 sudo 用 docker：sudo usermod -aG docker \$USER 然后重新登录"
}

cmd_load() {
    load_env
    [ -d "$IMAGE_DIR" ] || die "找不到镜像目录 $IMAGE_DIR"
    local found=0
    for f in "$IMAGE_DIR"/*.tar.gz; do
        [ -e "$f" ] || continue
        found=1
        log "导入 $(basename "$f")"
        gunzip -c "$f" | docker load
    done
    [ "$found" -eq 1 ] || die "$IMAGE_DIR 里没有 .tar.gz"
    docker image ls | grep -E 'alumni-link|mariadb' || true
}

cmd_up() {
    load_env
    [ -f "$HERE/docker-compose.yml" ] || die "缺少 docker-compose.yml"
    docker image inspect "$ALUMNI_IMAGE" >/dev/null 2>&1 \
        || die "镜像 $ALUMNI_IMAGE 不存在，先跑：bash $0 load"
    log "启动服务（端口 $APP_PORT）"
    compose up -d
    wait_db
    wait_app
    log "状态"
    compose ps
}

cmd_bootstrap() {
    load_env
    local before
    before=$(table_count || echo 0)
    if [ "${before:-0}" -gt 0 ] 2>/dev/null; then
        die "库里已经有 $before 张表。DDL 会 drop 掉它们，已拒绝执行。
     确实要重建，先停服务并删卷：docker compose down -v"
    fi
    log "首次建表（DDL_BOOTSTRAP=true，会 drop + create）"
    DDL_BOOTSTRAP=true compose up -d
    wait_db
    for _ in $(seq 1 60); do
        if [ "$(table_count || echo 0)" -gt 0 ] 2>/dev/null; then
            break
        fi
        sleep 2
    done
    local n
    n=$(table_count || echo 0)
    [ "${n:-0}" -gt 0 ] || die "表没有建出来，看日志：docker compose logs app"
    echo "  建好 $n 张表"

    # 注册接口把 roleId 写死成 4（student），sys_role 空表会导致注册直接失败
    local roles
    roles=$(db_sql -N -e "SELECT COUNT(*) FROM \`alumin-link\`.sys_role;" 2>/dev/null | tr -d '\r ')
    if [ "${roles:-0}" = "0" ]; then
        log "写入角色种子数据"
        db_sql -e "INSERT INTO \`alumin-link\`.sys_role (id,role_name,type,created_at,updated_at,deleted) VALUES
                     (1,'超级管理员','super_admin',NOW(6),NOW(6),0),
                     (2,'管理员','admin',NOW(6),NOW(6),0),
                     (4,'学生','student',NOW(6),NOW(6),0);"
        echo "  sys_role: super_admin / admin / student（id 4 必须是 student）"
    fi

    log "关掉 DDL 并重启应用"
    DDL_BOOTSTRAP=false compose up -d --force-recreate app
    wait_app
    sed -i 's|^DDL_BOOTSTRAP=.*|DDL_BOOTSTRAP=false|' "$HERE/.env"
    warn "下一步：前端注册一个账号，再把它提升为管理员（见部署文档）。"
}

cmd_verify() {
    load_env
    log "容器状态"
    compose ps
    log "数据库连接"
    compose logs app 2>&1 | grep -E 'Started database\[db\]' | tail -1 \
        || warn "日志里没看到 'Started database[db]'，数据库可能没连上"
    log "HTTP 检查"
    printf '  /          -> '; curl -s -o /dev/null -w '%{http_code}\n' --max-time 10 "http://127.0.0.1:${APP_PORT}/" || true
    printf '  /admin/db  -> '; curl -s -o /dev/null -w '%{http_code}\n' --max-time 10 "http://127.0.0.1:${APP_PORT}/admin/db" || true
    log "数据表数量"
    printf '  '; table_count || echo '?'
    echo
}

cmd_down() {
    load_env
    compose down
}

cmd_logs() {
    load_env
    compose logs -f --tail 100 app
}

cmd_help() {
    cat <<'EOF'
用法：bash debian-deploy.sh <命令>

  install-docker   安装 docker-ce + compose v2（需要 root）
  load             导入 images/*.tar.gz 镜像
  up               启动服务
  bootstrap        空库首次建表，完成后自动关闭 DDL
  verify           验收：容器状态 / 数据库连接 / HTTP / 表数量
  logs             跟踪应用日志
  down             停止服务（保留数据卷）
EOF
}

case "${1:-help}" in
    install-docker) cmd_install_docker ;;
    load)           cmd_load ;;
    up)             cmd_up ;;
    bootstrap)      cmd_bootstrap ;;
    verify)         cmd_verify ;;
    logs)           cmd_logs ;;
    down)           cmd_down ;;
    *)              cmd_help ;;
esac