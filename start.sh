#!/usr/bin/env bash
# One-shot launcher for this bundle on Linux: MariaDB 11.4 on 3307 + alumni-link on 7031
# + the 数据库管理页 on 8080. Everything resolves from this folder; nothing is installed
# as a service.
# Windows counterparts: start.cmd / stop.cmd (runtime/ holds Windows binaries, unused here).

set -u

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DB_IMAGE="mariadb:11.4"
DB_NAME="xyl-mariadb"
DB_DATA_WIN="$ROOT/runtime/dbdata/data"
DB_DATA="$ROOT/runtime/dbdata/data-linux"
JAR="$ROOT/build/libs/alumni-link-1.0.0-SNAPSHOT-fat.jar"
APP_LOG="$ROOT/runtime/logs/app.log"
APP_PID="$ROOT/runtime/app.pid"
DBPORT=3307
APPPORT=7031
ADMIN_PY="$ROOT/adminui/.venv/bin/python"
ADMIN_LOG="$ROOT/runtime/logs/adminui.log"
ADMIN_PID="$ROOT/runtime/adminui.pid"
ADMINPORT=8080
DBURL="jdbc:mariadb://127.0.0.1:$DBPORT/alumin-link?useUnicode=true&characterEncoding=utf8&serverTimezone=GMT%2B8"

port_up() { (ss -ltn 2>/dev/null || netstat -ltn 2>/dev/null) | grep -qE ":$1([^0-9]|$)"; }
java_bin() {
    if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then echo "$JAVA_HOME/bin/java"
    elif command -v java >/dev/null; then command -v java
    else return 1; fi
}

# ---------------- 1. MariaDB on $DBPORT ----------------
if ! docker info >/dev/null 2>&1; then
    echo "[db]   FAILED - docker daemon unavailable, and runtime/mariadb holds Windows binaries."
    exit 1
fi

if [ ! -d "$DB_DATA/mysql" ]; then
    echo "[db]   creating Linux copy of the bundled data dir (the original stays untouched)..."
    rm -rf "$DB_DATA"
    cp -a "$DB_DATA_WIN" "$DB_DATA" || { echo "[db]   FAILED - cannot copy $DB_DATA_WIN"; exit 1; }
    chmod -R u+rwX,go+rwX "$DB_DATA"
fi

if port_up "$DBPORT"; then
    echo "[db]   already running on 127.0.0.1:$DBPORT"
else
    if [ "$(docker ps -q -f name="^$DB_NAME\$" 2>/dev/null)" ]; then
        echo "[db]   FAILED - something else already holds $DBPORT."
        exit 1
    fi
    echo "[db]   starting $DB_IMAGE on $DBPORT..."
    docker rm -f "$DB_NAME" >/dev/null 2>&1
    if [ -z "$(docker images -q "$DB_IMAGE" 2>/dev/null)" ]; then
        docker pull "$DB_IMAGE" >/dev/null || { echo "[db]   FAILED - cannot pull $DB_IMAGE"; exit 1; }
    fi
    # host networking keeps the client address on 127.0.0.1, so the existing
    # root@127.0.0.1 grant in the copied data dir works without touching mysql.user.
    docker run -d --name "$DB_NAME" --restart unless-stopped --network host \
        -v "$DB_DATA":/var/lib/mysql \
        -e MARIADB_ROOT_PASSWORD=123456 \
        "$DB_IMAGE" \
        --port=$DBPORT --bind-address=127.0.0.1 \
        --character-set-server=utf8mb4 --collation-server=utf8mb4_general_ci \
        --sql-mode="STRICT_TRANS_TABLES,NO_ZERO_IN_DATE,NO_ZERO_DATE,ERROR_FOR_DIVISION_BY_ZERO,NO_ENGINE_SUBSTITUTION" \
        --innodb-buffer-pool-size=256M --max-connections=200 >/dev/null \
        || { echo "[db]   FAILED - docker run refused (container '$DB_NAME' may exist stopped; run: docker start $DB_NAME)"; exit 1; }
fi

tries=0
until port_up "$DBPORT"; do
    tries=$((tries+1))
    if [ $tries -ge 30 ]; then
        echo "[db]   FAILED - $DBPORT never opened. See: docker logs $DB_NAME"
        exit 1
    fi
    sleep 1
done
echo "[db]   ready on 127.0.0.1:$DBPORT"

# ---------------- 2. alumni-link on $APPPORT ----------------
if port_up "$APPPORT"; then
    echo "[app]  already running on http://127.0.0.1:$APPPORT - left untouched."
else

JAVA="$(java_bin)" || { echo "[env]  FAILED - no java on PATH, install JDK 21 or set JAVA_HOME."; exit 1; }
[ -f "$JAR" ] || { echo "[app]  FAILED - jar not found: $JAR"; echo "[app]  Build it: cd $ROOT && ./gradlew bootJar"; exit 1; }

mkdir -p "$(dirname "$APP_LOG")"
# CWD must be the bundle root: the app writes work/files and reads application.yaml relative to it.
cd "$ROOT" || exit 1
echo "[app]  starting alumni-link ($("$JAVA" -version 2>&1 | head -1)), log: $APP_LOG"
# --no-gui: this box is headless, so the Compose desktop window is skipped and Spring Boot runs alone.
nohup "$JAVA" \
    "-Dserver.port=$APPPORT" \
    "-Ddatasource.db.url=$DBURL" \
    -Debean.ddl.run=false -Debean.ddl.generate=false \
    -Debean.db.ddl.run=false -Debean.db.ddl.generate=false \
    "-Dcors.allowed-origins=http://localhost:$APPPORT,http://127.0.0.1:$APPPORT,http://localhost:8080,http://127.0.0.1:8080" \
    -jar "$JAR" --no-gui >>"$APP_LOG" 2>&1 &
echo $! > "$APP_PID"

tries=0
until port_up "$APPPORT"; do
    tries=$((tries+1))
    if ! kill -0 "$(cat "$APP_PID")" 2>/dev/null; then
        echo "[app]  FAILED - process exited. Tail of $APP_LOG:"
        tail -20 "$APP_LOG"
        rm -f "$APP_PID"
        exit 1
    fi
    if [ $tries -ge 120 ]; then
        echo "[app]  FAILED - $APPPORT never opened after 120s. Tail of $APP_LOG:"
        tail -20 "$APP_LOG"
        exit 1
    fi
    sleep 1
done
echo "[app]  ready on http://127.0.0.1:$APPPORT"
fi

# ---------------- 3. 数据库管理页 on $ADMINPORT ----------------
if port_up "$ADMINPORT"; then
    echo "[ui]   already running on http://127.0.0.1:$ADMINPORT - left untouched."
else
    if [ ! -x "$ADMIN_PY" ]; then
        echo "[ui]   creating adminui venv (python + pymysql)..."
        rm -rf "$ROOT/adminui/.venv"
        python3 -m venv "$ROOT/adminui/.venv" >/dev/null 2>&1 \
            && "$ADMIN_PY" -m pip install --quiet --disable-pip-version-check pymysql \
            || { echo "[ui]   FAILED - cannot build $ROOT/adminui/.venv (offline? run: python3 -m venv adminui/.venv && adminui/.venv/bin/pip install pymysql)"; exit 1; }
    fi
    mkdir -p "$(dirname "$ADMIN_LOG")"
    echo "[ui]   starting 管理页 on http://127.0.0.1:$ADMINPORT (schema alumin-link), log: $ADMIN_LOG"
    # 只绑定 127.0.0.1：管理页没有登录鉴权，这个端口不能对 box 外暴露。
    XYL_DB_HOST=127.0.0.1 XYL_DB_PORT=$DBPORT XYL_DB_SCHEMA="alumin-link" \
    XYL_ADMIN_BIND=127.0.0.1 XYL_ADMIN_PORT=$ADMINPORT \
    nohup "$ADMIN_PY" "$ROOT/adminui/server.py" >>"$ADMIN_LOG" 2>&1 &
    echo $! > "$ADMIN_PID"

    tries=0
    until port_up "$ADMINPORT"; do
        tries=$((tries+1))
        if ! kill -0 "$(cat "$ADMIN_PID")" 2>/dev/null; then
            echo "[ui]   FAILED - process exited. Tail of $ADMIN_LOG:"
            tail -20 "$ADMIN_LOG"
            rm -f "$ADMIN_PID"
            exit 1
        fi
        if [ $tries -ge 20 ]; then
            echo "[ui]   FAILED - $ADMINPORT never opened. Tail of $ADMIN_LOG:"
            tail -20 "$ADMIN_LOG"
            exit 1
        fi
        sleep 1
    done
    echo "[ui]   ready on http://127.0.0.1:$ADMINPORT"
fi

echo
echo "open    http://127.0.0.1:$APPPORT  for the app,  http://127.0.0.1:$ADMINPORT  for 管理页"
echo "stop everything with ./stop.sh"
