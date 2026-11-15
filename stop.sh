#!/usr/bin/env bash
# One-click stop for this bundle: 管理页 on 8080, alumni-link on 7031, then MariaDB on 3307.
# Each process is found by the pid owning its port and verified against this bundle's own
# command line, so a Gradle daemon or any other java/python process is never killed. The
# database is stopped with docker stop (SIGTERM) so InnoDB flushes instead of being cut short.

set -u

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DB_NAME="xyl-mariadb"
# Absolute jar path: with two installs sharing port 7031, matching only the jar name could
# let this script kill the other install's process.
JAR="$ROOT/build/libs/alumni-link-1.0.0-SNAPSHOT-fat.jar"
APP_PID="$ROOT/runtime/app.pid"
ADMIN_PID="$ROOT/runtime/adminui.pid"
APPPORT=7031
ADMINPORT=8080
DBPORT=3307

port_pid() { ss -ltnp 2>/dev/null | grep -E ":$1([^0-9]|$)" | grep -oE 'pid=[0-9]+' | head -1 | cut -d= -f2; }
port_up() { (ss -ltn 2>/dev/null || netstat -ltn 2>/dev/null) | grep -qE ":$1([^0-9]|$)"; }

# ---------------- 1. 管理页 + alumni-link ----------------
stop_proc() {
    local label="$1" port="$2" pidfile="$3" needle="$4" pid=""
    [ -f "$pidfile" ] && pid="$(cat "$pidfile")"
    if [ -z "$pid" ] || ! kill -0 "$pid" 2>/dev/null; then
        pid="$(port_pid "$port")"
    fi
    if [ -z "$pid" ] || ! kill -0 "$pid" 2>/dev/null; then
        echo "[$label] not running."
    elif ! tr '\0' ' ' < "/proc/$pid/cmdline" 2>/dev/null | grep -q "$needle"; then
        echo "[$label] REFUSED - pid $pid owns $port but is not this bundle's $needle, left alone."
    else
        echo "[$label] stopping (pid $pid)..."
        kill "$pid" 2>/dev/null
        local tries=0
        while port_up "$port"; do
            tries=$((tries+1))
            if [ $tries -ge 15 ]; then
                echo "[$label] still holding $port after 15s, sending SIGKILL."
                kill -9 "$pid" 2>/dev/null
                break
            fi
            sleep 1
        done
        echo "[$label] stopped."
    fi
    rm -f "$pidfile"
}

stop_proc "ui"  "$ADMINPORT" "$ADMIN_PID" "$ROOT/adminui/server.py"
stop_proc "app" "$APPPORT"   "$APP_PID"   "$JAR"

# ---------------- 2. MariaDB ----------------
if ! port_up $DBPORT; then
    echo "[db]   not running."
elif docker ps -q -f name="^${DB_NAME}\$" | grep -q .; then
    echo "[db]   shutting down MariaDB (graceful, InnoDB flushes)..."
    docker stop -t 60 "$DB_NAME" >/dev/null || echo "[db]   FAILED - docker stop refused; container '$DB_NAME' left running."
    echo "[db]   stopped."
else
    echo "[db]   $DBPORT is held by something that is not the '$DB_NAME' container - left alone."
fi

echo
echo "everything stopped - restart with ./start.sh"
