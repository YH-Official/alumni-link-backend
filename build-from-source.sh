#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────
# 在一台全新的 Debian 上从源码构建并部署校友录后端。
#
#   sudo bash build-from-source.sh all      # 一条命令走完
#
# 也可以分步：
#   sudo bash build-from-source.sh deps     # 装 Docker + JDK 21 + git（需要 root）
#   bash build-from-source.sh build         # 构建 jar 和镜像
#   bash build-from-source.sh db            # 建库 + 导入 alumni-dump.sql
#   bash build-from-source.sh up            # 启动应用
#   bash build-from-source.sh verify        # 验收
#
# 前提：在仓库根目录执行（docker-compose.yml、gradlew、alumni-dump.sql 都在这）。
# 离线/无网环境不要用这个脚本，用 debian-deploy.sh + 镜像 tar.gz。
# ─────────────────────────────────────────────────────────────
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$HERE"

APP_IMAGE="${ALUMNI_IMAGE:-alumni-link:1.0.0}"
DUMP="$HERE/alumni-dump.sql"
DOCKER_MIRROR="${DOCKER_APT_MIRROR:-https://mirrors.aliyun.com/docker-ce}"
JDK_TARBALL_VERSION="${JDK_TARBALL_VERSION:-21.0.13.1_2}"
JDK_TUNA="https://mirrors.tuna.tsinghua.edu.cn/Adoptium/21/jdk"

log()  { printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }
warn() { printf '\033[1;33m[注意]\033[0m %s\n' "$*" >&2; }
die()  { printf '\n\033[1;31m[错误]\033[0m %s\n' "$*" >&2; exit 1; }

need_root() { [ "$(id -u)" -eq 0 ] || die "这一步要 root：sudo bash $0 ${1:-}"; }

compose() {
    if docker compose version >/dev/null 2>&1; then
        docker compose "$@"
    else
        die "缺少 docker compose v2 插件。Debian 自带的 docker.io 不带它，
     请执行：sudo bash $0 deps"
    fi
}

# ── 环境 ──────────────────────────────────────────────────────

check_os() {
    [ -r /etc/os-release ] || die "读不到 /etc/os-release"
    # shellcheck disable=SC1091
    . /etc/os-release
    case "${ID}${ID_LIKE:-}" in
        *debian*) ;;
        *) die "这个脚本只针对 Debian，当前是 ${PRETTY_NAME:-未知}" ;;
    esac
    echo "  系统: ${PRETTY_NAME}"
    echo "  架构: $(uname -m)"
    echo "  内存: $(free -h | awk '/^Mem:/{print $2}')，磁盘 $(df -h . | awk 'NR==2{print $4}') 可用"
}

has_jdk21() {
    local jc
    jc="$(command -v javac || true)"
    [ -n "$jc" ] || return 1
    "$jc" -version 2>&1 | grep -q '^javac 21\.'
}

install_docker() {
    if command -v docker >/dev/null 2>&1 && docker compose version >/dev/null 2>&1; then
        log "docker 已安装：$(docker --version)"
        return 0
    fi
    log "安装 Docker（源：$DOCKER_MIRROR）"
    apt-get update
    DEBIAN_FRONTEND=noninteractive apt-get install -y ca-certificates curl gnupg

    install -m 0755 -d /etc/apt/keyrings
    curl -fsSL --max-time 30 "$DOCKER_MIRROR/linux/debian/gpg" \
        | gpg --dearmor -o /etc/apt/keyrings/docker.gpg \
        || die "下载 Docker GPG 公钥失败，检查网络或换 DOCKER_APT_MIRROR=https://download.docker.com"
    chmod a+r /etc/apt/keyrings/docker.gpg

    echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] $DOCKER_MIRROR/linux/debian ${VERSION_CODENAME} stable" \
        > /etc/apt/sources.list.d/docker.list

    apt-get update
    DEBIAN_FRONTEND=noninteractive apt-get install -y \
        docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
    systemctl enable --now docker
    docker --version
    docker compose version
}

configure_registry_mirror() {
    local f=/etc/docker/daemon.json
    if [ -f "$f" ] && grep -q 'registry-mirrors' "$f"; then
        log "registry-mirrors 已配置，跳过"
        return 0
    fi
    if [ -f "$f" ]; then
        warn "$f 已存在但不含 registry-mirrors，不覆盖。若拉不到基础镜像，"
        warn "请手动往里加：\"registry-mirrors\": [\"https://docker.1ms.run\"]"
        return 0
    fi
    log "配置镜像加速（拉 eclipse-temurin 基础镜像用）"
    mkdir -p /etc/docker
    cat > "$f" <<'EOF'
{
  "registry-mirrors": ["https://docker.1ms.run", "https://docker.m.daocloud.io"]
}
EOF
    systemctl restart docker
}

install_jdk() {
    if has_jdk21; then
        log "JDK 已就绪：$(javac -version 2>&1)"
        return 0
    fi

    log "安装 JDK 21"
    apt-get update
    if apt-cache show openjdk-21-jdk-headless >/dev/null 2>&1; then
        # Debian 13 (trixie) 主源就有
        DEBIAN_FRONTEND=noninteractive apt-get install -y openjdk-21-jdk-headless
    else
        # Debian 12 (bookworm) 主源只有 17，backports 也没有 21，用清华的 Temurin 包
        warn "当前发行版源里没有 openjdk-21，改用清华 Adoptium 镜像"
        local arch
        case "$(uname -m)" in
            x86_64)  arch=x64 ;;
            aarch64) arch=aarch64 ;;
            *) die "不支持的架构 $(uname -m)" ;;
        esac
        local name="OpenJDK21U-jdk_${arch}_linux_hotspot_${JDK_TARBALL_VERSION}.tar.gz"
        cd /opt
        curl -fLO --max-time 600 "$JDK_TUNA/$arch/linux/$name" \
            || die "下载 JDK 失败：$JDK_TUNA/$arch/linux/$name"
        tar xzf "$name"
        rm -f "$name"
        rm -rf /opt/jdk21
        mv jdk-21* /opt/jdk21
        cat > /etc/profile.d/jdk21.sh <<'EOF'
export JAVA_HOME=/opt/jdk21
export PATH=$JAVA_HOME/bin:$PATH
EOF
        chmod +x /etc/profile.d/jdk21.sh
        # shellcheck disable=SC1091
        . /etc/profile.d/jdk21.sh
    fi

    has_jdk21 || die "JDK 21 安装后仍不可用，检查 PATH"
    log "JDK 就绪：$(javac -version 2>&1)"
}

# ── 数据库 ────────────────────────────────────────────────────

load_env() {
    if [ ! -f "$HERE/.env" ]; then
        [ -f "$HERE/.env.example" ] || die "缺少 .env 和 .env.example"
        cp "$HERE/.env.example" "$HERE/.env"
        local pw
        pw=$(head -c 32 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | head -c 24)
        sed -i "s|^DB_PASSWORD=.*|DB_PASSWORD=$pw|" "$HERE/.env"
        warn "已生成数据库密码并存进 .env，请备份：$pw"
    fi
    # shellcheck disable=SC1091
    set -a; . "$HERE/.env"; set +a
    DB_PASSWORD="${DB_PASSWORD:?密码未设置}"
    APP_PORT="${APP_PORT:-7031}"
    DDL_BOOTSTRAP="${DDL_BOOTSTRAP:-false}"
    export DB_PASSWORD APP_PORT DDL_BOOTSTRAP ALUMNI_IMAGE="${ALUMNI_IMAGE:-$APP_IMAGE}"
}

wait_health() {
    local svc="$1" limit="${2:-60}"
    log "等待 $svc 就绪"
    for _ in $(seq 1 "$limit"); do
        if compose ps --format '{{.Service}} {{.Health}}' 2>/dev/null | grep -q "^${svc} healthy"; then
            echo "  $svc 已就绪"
            return 0
        fi
        sleep 2
    done
    compose logs --tail 40 "$svc" || true
    die "$svc 在 $((limit * 2)) 秒内没有就绪"
}

db_sql() {
    compose exec -T db mariadb -uroot -p"$DB_PASSWORD" "$@"
}

table_count() {
    db_sql -N -e "SELECT COUNT(*) FROM information_schema.tables
                  WHERE table_schema='alumin-link' AND table_type='BASE TABLE';" 2>/dev/null | tr -d '\r '
}

print_counts() {
    db_sql -N -e "
      SELECT 'sys_user', COUNT(*) FROM \`alumin-link\`.sys_user UNION ALL
      SELECT 'alk_user_profile', COUNT(*) FROM \`alumin-link\`.alk_user_profile UNION ALL
      SELECT 'alk_forum_category', COUNT(*) FROM \`alumin-link\`.alk_forum_category UNION ALL
      SELECT 'alk_school', COUNT(*) FROM \`alumin-link\`.alk_school UNION ALL
      SELECT 'oss_user_file', COUNT(*) FROM \`alumin-link\`.oss_user_file UNION ALL
      SELECT 'sys_role', COUNT(*) FROM \`alumin-link\`.sys_role;" 2>/dev/null \
      | awk -F'\t' '{printf "  %-20s %s\n", $1, $2}'
}

# ── 命令 ──────────────────────────────────────────────────────

cmd_deps() {
    need_root deps
    check_os
    install_docker
    configure_registry_mirror
    install_jdk
    DEBIAN_FRONTEND=noninteractive apt-get install -y git
    log "依赖就绪"
    docker --version
    docker compose version
    javac -version
    warn "普通用户想免 sudo 用 docker：sudo usermod -aG docker \$USER 然后重新登录"
}

cmd_build() {
    cd "$HERE"
    has_jdk21 || die "没有 JDK 21。先跑：sudo bash $0 deps"
    [ -x gradlew ] || chmod +x gradlew

    log "构建 fat jar（首次约 10 分钟）"
    ./gradlew bootJar

    local jar
    jar=$(ls -1 build/libs/*-fat.jar 2>/dev/null | head -1 || true)
    [ -n "$jar" ] || die "没有生成 *-fat.jar，看上面的 Gradle 输出"
    echo "  产物: $jar（$(du -h "$jar" | cut -f1)）"

    log "构建镜像 $APP_IMAGE"
    docker build -t "$APP_IMAGE" .
    docker image inspect "$APP_IMAGE" >/dev/null
    echo "  镜像就绪: $(docker images --format '{{.Repository}}:{{.Tag}} {{.Size}}' | grep "^$APP_IMAGE " || true)"
}

cmd_db() {
    load_env
    docker image inspect "$APP_IMAGE" >/dev/null 2>&1 \
        || die "镜像 $APP_IMAGE 不存在，先跑：bash $0 build"

    log "启动数据库"
    compose up -d db
    wait_health db

    local n
    n=$(table_count || echo 0)
    if [ "${n:-0}" -gt 0 ]; then
        log "库里已有 $n 张表，跳过导入"
    else
        [ -f "$DUMP" ] || die "缺少 $DUMP"
        log "导入 $DUMP"
        db_sql --default-character-set=utf8mb4 < "$DUMP"
    fi

    log "表数量与关键表行数"
    echo "  表数量: $(table_count)"
    print_counts
}

cmd_up() {
    load_env
    log "启动应用（端口 $APP_PORT）"
    compose up -d
    wait_health db
    wait_health app
    compose ps
}

cmd_verify() {
    load_env
    log "容器状态"
    compose ps

    log "数据库连接"
    compose logs app 2>&1 | grep -E 'Started database\[db\]' | tail -1 \
        || warn "日志里没有 'Started database[db]'"

    log "HTTP"
    printf '  /          -> '; curl -s -o /dev/null -w '%{http_code}\n' --max-time 10 "http://127.0.0.1:${APP_PORT}/" || echo '?'
    printf '  /admin/db  -> '; curl -s -o /dev/null -w '%{http_code}\n' --max-time 10 "http://127.0.0.1:${APP_PORT}/admin/db" || echo '?'

    log "数据"
    echo "  表数量: $(table_count)"
    print_counts
}

cmd_help() {
    cat <<'EOF'
用法：bash build-from-source.sh <命令>

  deps     装 Docker + compose 插件 + 镜像加速 + JDK 21 + git（需要 root）
  build    ./gradlew bootJar 并构建 docker 镜像
  db       启动数据库、导入 alumni-dump.sql、打印行数
  up       启动应用并等待健康
  verify   验收：容器 / 数据库连接 / HTTP / 数据
  all      deps + build + db + up + verify

离线环境请改用 debian-deploy.sh（镜像 tar.gz 那条路）。
EOF
}

case "${1:-help}" in
    deps)   cmd_deps ;;
    build)  cmd_build ;;
    db)     cmd_db ;;
    up)     cmd_up ;;
    verify) cmd_verify ;;
    all)
        cmd_deps
        cmd_build
        cmd_db
        cmd_up
        cmd_verify
        ;;
    *) cmd_help ;;
esac