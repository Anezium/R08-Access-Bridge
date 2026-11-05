#!/system/bin/sh
# Run on Android: sh test-r08-assistant-pin.sh /path/to/r08-shortcut-bridge.sh
# Stubs input/dumpsys: no keys reach the real foreground app.
set -eu
SCRIPT="$1"
TEST_DIR="/data/local/tmp/r08-assistant-pin-test.$$"
mkdir -p "$TEST_DIR/bridge" "$TEST_DIR/bin"
export R08_BRIDGE_DIR="$TEST_DIR/bridge"
export R08_PIDFILE="$TEST_DIR/helper.pid"
export R08_LOGFILE="$TEST_DIR/helper.log"
export R08_IDLE_RECHECK_SECONDS=1
export R08_TEST_FOCUS="$TEST_DIR/focus"
export R08_TEST_INPUT="$TEST_DIR/input"
export PATH="$TEST_DIR/bin:$PATH"
cat > "$TEST_DIR/bin/dumpsys" <<'EOF'
#!/system/bin/sh
cat "$R08_TEST_FOCUS"
EOF
cat > "$TEST_DIR/bin/input" <<'EOF'
#!/system/bin/sh
echo "$*" >> "$R08_TEST_INPUT"
EOF
chmod 755 "$TEST_DIR/bin/dumpsys" "$TEST_DIR/bin/input"
cleanup() {
    sh "$SCRIPT" stop >/dev/null 2>&1 || true
    rm -f "$TEST_DIR/bin/dumpsys" "$TEST_DIR/bin/input" "$TEST_DIR/bridge/"* \
        "$TEST_DIR/helper.pid" "$TEST_DIR/helper.log" "$TEST_DIR/focus" "$TEST_DIR/input"
    rmdir "$TEST_DIR/bin" "$TEST_DIR/bridge" "$TEST_DIR"
}
trap cleanup EXIT
uptime_ms() {
    read -r seconds rest < /proc/uptime
    echo "$(( ${seconds%%.*} * 1000 ))"
}
: > "$R08_BRIDGE_DIR/request"
sh "$SCRIPT" start >/dev/null
for attempt in $(seq 1 40); do
    [ -f "$R08_BRIDGE_DIR/entry_capability" ] && break
    sleep 0.05
done
read -r version session timestamp < "$R08_BRIDGE_DIR/entry_capability"
grep -qw assistant_pin "$R08_BRIDGE_DIR/features"
grep -qw assistant_scroll "$R08_BRIDGE_DIR/features"
send_request() {
    echo "$session $1 $2 $3 ${5:-}" > "$R08_BRIDGE_DIR/entry_request"
    echo wake > "$R08_BRIDGE_DIR/doorbell"
    for attempt in $(seq 1 40); do
        if [ "$(cat "$R08_BRIDGE_DIR/entry_response" 2>/dev/null)" = "$session $1 $4" ]; then
            return 0
        fi
        sleep 0.05
    done
    echo "FAIL: expected $1 $4"
    cat "$R08_BRIDGE_DIR/entry_response" 2>/dev/null || true
    exit 1
}
echo 'mCurrentFocus=Window{123 u0 com.rokid.os.sprite.assistserver}' > "$R08_TEST_FOCUS"
send_request pin "$(( $(uptime_ms) + 3000 ))" 66 ok
[ "$(cat "$R08_TEST_INPUT")" = 'keyevent 66' ]
echo wake > "$R08_BRIDGE_DIR/doorbell"
sleep 0.15
[ "$(wc -l < "$R08_TEST_INPUT" | tr -d ' ')" = 1 ]
echo 'PASS: native Enter delivered once; duplicate request not replayed'
send_request down "$(( $(uptime_ms) + 3000 ))" 20 ok assistant
[ "$(tail -n 1 "$R08_TEST_INPUT")" = 'keyevent 20' ]
send_request up "$(( $(uptime_ms) + 3000 ))" 19 ok assistant
[ "$(tail -n 1 "$R08_TEST_INPUT")" = 'keyevent 19' ]
echo 'PASS: both scroll directions target the native assistant'
send_request expired 1 66 expired
echo 'mCurrentFocus=Window{456 u0 another.app/.MainActivity}' > "$R08_TEST_FOCUS"
send_request background "$(( $(uptime_ms) + 3000 ))" 66 foreground
send_request invalid "$(( $(uptime_ms) + 3000 ))" 67 invalid
send_request background-scroll "$(( $(uptime_ms) + 3000 ))" 20 foreground assistant
send_request invalid-target "$(( $(uptime_ms) + 3000 ))" 19 invalid another
[ "$(wc -l < "$R08_TEST_INPUT" | tr -d ' ')" = 3 ]
echo 'PASS: expired, background and unsupported keys rejected'
echo 'topResumedActivity=ActivityRecord{789 u0 com.rokid.os.sprite.launcher/.main.SpriteMainActivity}' > "$R08_TEST_FOCUS"
send_request launcher "$(( $(uptime_ms) + 3000 ))" 20 ok
[ "$(tail -n 1 "$R08_TEST_INPUT")" = 'keyevent 20' ]
echo 'PASS: existing launcher entry still works'
