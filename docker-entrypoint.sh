#!/bin/sh
# ─────────────────────────────────────────────────────────────
# 容器入口。改写 /app/application.yaml，然后交给 java 启动 Spring Boot。
#
# 支持的环境变量：
#   DB_URL          -> datasource.db.url
#   DB_USER         -> datasource.db.username（文件里没有就补一行）
#   DB_PASSWORD     -> datasource.db.password（文件里没有就补一行）
#   DDL_BOOTSTRAP   -> true 时把 ebean.ddl.* 的 generate/run 全改成 true，
#                      用于空库首次建表。建完必须去掉，否则每次启动都会删表重建！
#   JAVA_OPTS       -> 追加 JVM 参数
#   APP_ARGS        -> 追加程序参数
#
# 为什么必须改文件：外置 application.yaml 的优先级高于 -D 系统属性，
# 光传 -Ddatasource.db.url=... 不生效（实测仍连 127.0.0.1:3307）。
# 想完全接管配置，直接把自己的 application.yaml 挂到 /app/application.yaml，
# 上面这些变量都可以不给；文件只读（:ro）也不会中断启动。
# ─────────────────────────────────────────────────────────────
set -eu

CONFIG=/app/application.yaml

# 把临时文件原地写回。不能用 sed -i：
# 配置是 bind mount 时 -i 的 rename 会报 Device or resource busy。
apply_config() {
    tmp=$(mktemp 2>/dev/null || printf '/tmp/app-yaml.%s' "$$")
    if "$@" "$CONFIG" > "$tmp" 2>/dev/null && cat "$tmp" > "$CONFIG" 2>/dev/null; then
        rm -f "$tmp"
        return 0
    fi
    rm -f "$tmp"
    return 1
}

if [ ! -f "$CONFIG" ]; then
    echo "[entrypoint] 警告：$CONFIG 不存在，配置无法改写" >&2
else
    # ── 数据库连接 ──
    # 只改第一处 "url:"（也就是 datasource.db.url），顺带补齐 username/password；
    # 原来紧跟的两行若存在就丢掉，避免 YAML 重复键。
    if [ -n "${DB_URL:-}" ] || [ -n "${DB_USER:-}" ] || [ -n "${DB_PASSWORD:-}" ]; then
        if apply_config awk -v url="${DB_URL:-}" -v user="${DB_USER:-}" -v pass="${DB_PASSWORD:-}" '
                function q(s) { gsub(/\\/, "\\\\", s); gsub(/"/, "\\\"", s); return "\"" s "\"" }
                /^[[:space:]]*url:/ && !done {
                    indent = $0
                    sub(/[^[:space:]].*$/, "", indent)
                    print (url == "" ? $0 : indent "url: " q(url))
                    if (user != "") print indent "username: " q(user)
                    if (pass != "") print indent "password: " q(pass)
                    done = 1
                    skip = 2
                    next
                }
                skip > 0 && /^[[:space:]]*(username|password):/ { skip--; next }
                { print }
            '; then
            [ -n "${DB_URL:-}" ]      && echo "[entrypoint] datasource.db.url <- $DB_URL"
            [ -n "${DB_USER:-}" ]     && echo "[entrypoint] datasource.db.username <- $DB_USER"
            [ -n "${DB_PASSWORD:-}" ] && echo "[entrypoint] datasource.db.password 已更新"
        else
            echo "[entrypoint] 提示：$CONFIG 不可写，保留原数据库配置" >&2
        fi
    fi

    # ── 首次建库 ──
    if [ "${DDL_BOOTSTRAP:-false}" = "true" ]; then
        if apply_config awk '
                /^[[:space:]]*(generate|run):[[:space:]]*false/ { sub(/:.*/, ": true") }
                { print }
            '; then
            echo "[entrypoint] DDL_BOOTSTRAP=true：本次启动会 drop + create 全部表"
        else
            echo "[entrypoint] 警告：$CONFIG 不可写，DDL_BOOTSTRAP 未生效" >&2
        fi
    fi
fi

exec java ${JAVA_OPTS:-} -jar /app/app.jar --no-gui ${APP_ARGS:-}