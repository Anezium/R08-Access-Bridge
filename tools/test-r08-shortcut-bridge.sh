#!/system/bin/sh
# Run on Android: sh test-r08-shortcut-bridge.sh /path/to/r08-shortcut-bridge.sh
set -eu

SCRIPT="$1"
TEST_DIR="/data/local/tmp/r08-shortcut-test.$$"
mkdir -p "$TEST_DIR/bridge"
export R08_BRIDGE_DIR="$TEST_DIR/bridge"
export R08_PIDFILE="$TEST_DIR/helper.pid"
export R08_LOGFILE="$TEST_DIR/helper.log"
sleep 60 &
foreign_pid=$!

cleanup() {
    sh "$SCRIPT" stop >/dev/null 2>&1 || true
    kill "$foreign_pid" 2>/dev/null || true
    rm -f "$TEST_DIR/helper.pid" "$TEST_DIR/helper.log" \
        "$TEST_DIR/bridge/request" "$TEST_DIR/bridge/heartbeat" "$TEST_DIR/bridge/doorbell"
    rmdir "$TEST_DIR/bridge" "$TEST_DIR"
}
trap cleanup EXIT

echo "$foreign_pid" > "$R08_PIDFILE"
if sh "$SCRIPT" status; then
    echo 'FAIL: unrelated process accepted as helper'
    exit 1
fi
sh "$SCRIPT" stop
kill -0 "$foreign_pid"
echo 'PASS: stale PID rejected and unrelated process preserved'

echo "$foreign_pid" > "$R08_PIDFILE"
: > "$R08_BRIDGE_DIR/request"
sh "$SCRIPT" start
helper_pid="$(cat "$R08_PIDFILE")"
[ "$helper_pid" != "$foreign_pid" ]
sh "$SCRIPT" status
sh "$SCRIPT" start
[ "$(cat "$R08_PIDFILE")" = "$helper_pid" ]
sh "$SCRIPT" stop
kill -0 "$foreign_pid"
echo 'PASS: replacement helper starts, repeated start is idempotent, stop is safe'
