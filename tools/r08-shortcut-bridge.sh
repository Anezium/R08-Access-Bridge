#!/system/bin/sh

PKG="${R08_PACKAGE:-com.anezium.r08accessbridge}"
BASE="${R08_BRIDGE_DIR:-/sdcard/Android/data/$PKG/files/shortcut_bridge}"
REQUEST="$BASE/request"
RESPONSE="$BASE/response"
HEARTBEAT="$BASE/heartbeat"
DOORBELL="$BASE/doorbell"
ENTRY_CAPABILITY="$BASE/entry_capability"
ENTRY_REQUEST="$BASE/entry_request"
ENTRY_RESPONSE="$BASE/entry_response"
ENTRY_VERSION=1
FEATURES="$BASE/features"
# Space-separated capabilities the app checks before routing a request to this helper.
FEATURE_LIST="function_key ai_key"
PIDFILE="${R08_PIDFILE:-/data/local/tmp/r08-shortcut-bridge.pid}"
LOGFILE="${R08_LOGFILE:-/data/local/tmp/r08-shortcut-bridge.log}"
INPUT_DEVICE="${R08_INPUT_DEVICE:-/dev/input/event1}"
SETTINGS_SCAN_CODE="${R08_SETTINGS_SCAN_CODE:-149}"
# KEY_PROG1 on the touchpad (PROG_BLUE). Held past 300 ms, the framework sends ACTION_AI_START,
# which opens Rokid AI in the user's assistant mode without the phone.
AI_INPUT_NAME="${R08_AI_INPUT_NAME:-ROKID,PSOC-TP-R}"
AI_SCAN_CODE="${R08_AI_SCAN_CODE:-148}"
AI_HOLD_SECONDS="${R08_AI_HOLD_SECONDS:-0.6}"
FUNCTION_INPUT_NAME="${R08_FUNCTION_INPUT_NAME:-qpnp_pon}"
FUNCTION_SCAN_CODE="${R08_FUNCTION_SCAN_CODE:-139}"
# The framework long-press threshold for the camera button is 400 ms; very long press
# (3 s) is reserved for power and reset gestures, so stay well clear of it.
FUNCTION_SHORT_HOLD_SECONDS="${R08_FUNCTION_SHORT_HOLD_SECONDS:-0.08}"
FUNCTION_LONG_HOLD_SECONDS="${R08_FUNCTION_LONG_HOLD_SECONDS:-0.8}"
# Presses closer than the multi-press window add up (3 = pairing, 6 = factory reset
# countdown when the temples are folded), so injected presses are always spaced out.
# The assistserver also drops camera and AI actions within 1 s of each other.
FUNCTION_MIN_GAP_MS="${R08_FUNCTION_MIN_GAP_MS:-1000}"
POLL_SECONDS="${R08_POLL_SECONDS:-0.2}"
IDLE_RECHECK_SECONDS="${R08_IDLE_RECHECK_SECONDS:-5}"
WIFI_DISABLE_DELAY_SECONDS="${R08_WIFI_DISABLE_DELAY_SECONDS:-12}"

log_msg() {
    echo "$(date +%s) $*" >> "$LOGFILE"
}

uptime_ms() {
    read -r uptime_value uptime_idle < /proc/uptime
    uptime_fraction="${uptime_value#*.}000"
    uptime_fraction="${uptime_fraction%${uptime_fraction#???}}"
    echo "$(( ${uptime_value%%.*} * 1000 + 1$uptime_fraction - 1000 ))"
}

publish_entry_capability() {
    echo "$ENTRY_VERSION $entry_session $(uptime_ms)" > "$ENTRY_CAPABILITY.tmp"
    mv -f "$ENTRY_CAPABILITY.tmp" "$ENTRY_CAPABILITY"
}

entry_reply() {
    echo "$entry_session $entry_token $1" > "$ENTRY_RESPONSE.tmp"
    mv -f "$ENTRY_RESPONSE.tmp" "$ENTRY_RESPONSE"
}

check_entry_request() {
    entry_current="$(cat "$ENTRY_REQUEST" 2>/dev/null)"
    [ -n "$entry_current" ] && [ "$entry_current" != "$entry_last" ] || return
    # Consume before injecting. A lost response must never replay a key.
    entry_last="$entry_current"
    read -r entry_requested_session entry_token entry_expiry entry_key entry_extra <<EOF
$entry_current
EOF
    [ "$entry_requested_session" = "$entry_session" ] || return
    case "$entry_token" in ''|*[!a-zA-Z0-9-]*) return ;; esac
    case "$entry_expiry" in ''|*[!0-9]*) entry_reply invalid; return ;; esac
    case "$entry_key" in 19|20) ;; *) entry_reply invalid; return ;; esac
    [ -z "$entry_extra" ] || { entry_reply invalid; return; }
    [ "$(uptime_ms)" -lt "$entry_expiry" ] || { entry_reply expired; return; }
    # This is a global input injection: never deliver to an app opened while waiting.
    if ! dumpsys activity activities | grep -E 'mResumedActivity:|topResumedActivity=' \
            | grep -q 'com.rokid.os.sprite.launcher/.*SpriteMainActivity'; then
        entry_reply foreground
        return
    fi
    [ "$(uptime_ms)" -lt "$entry_expiry" ] || { entry_reply expired; return; }
    [ "$(cat "$ENTRY_REQUEST" 2>/dev/null)" = "$entry_current" ] || return
    if input keyevent "$entry_key"; then
        entry_reply ok
        log_msg "native entry injected key=$entry_key"
    else
        entry_reply failed
    fi
}

is_alive() {
    pid="$1"
    case "$pid" in
        ''|*[!0-9]*) return 1 ;;
    esac
    [ -d "/proc/$pid" ] || return 1
    # PID files survive reboots and the old PID can belong to an unrelated process.
    # Check the helper command before treating it as running (or sending it a signal).
    # Read through cat: toybox tr spins forever on the read error of a process exiting meanwhile.
    cmdline="$(cat "/proc/$pid/cmdline" 2>/dev/null | tr '\000' ' ')"
    case "$cmdline" in
        *"sh $0 run "*) return 0 ;;
        *) return 1 ;;
    esac
}

read_pid() {
    cat "$PIDFILE" 2>/dev/null
}

trigger_shortcut() {
    sendevent "$INPUT_DEVICE" 1 "$SETTINGS_SCAN_CODE" 1
    sendevent "$INPUT_DEVICE" 0 0 0
    sendevent "$INPUT_DEVICE" 1 "$SETTINGS_SCAN_CODE" 0
    sendevent "$INPUT_DEVICE" 0 0 0
}

input_device_named() {
    for name_file in /sys/class/input/event*/device/name; do
        [ "$(cat "$name_file" 2>/dev/null)" = "$1" ] || continue
        event_dir="${name_file%/device/name}"
        echo "/dev/input/${event_dir##*/}"
        return 0
    done
    return 1
}

# Firmware 1.28 stopped accepting the assistserver scene broadcast from user builds, and the
# button broadcasts are protected. A real key press on the camera button still reaches it.
# Remembers the key while it is down: a helper stopped mid-press must not leave it held, where
# the camera button's 3 s press is reserved for power and reset gestures.
hold_key() {
    sendevent "$1" 1 "$2" 1 || return 1
    held_device="$1"
    held_code="$2"
    sendevent "$1" 0 0 0
    sleep "$3"
    release_held_key
}

release_held_key() {
    [ -n "$held_device" ] || return 0
    sendevent "$held_device" 1 "$held_code" 0
    sendevent "$held_device" 0 0 0
    held_device=""
}

wait_function_gap() {
    if [ -n "$function_last_ms" ]; then
        wait_ms=$(( function_last_ms + FUNCTION_MIN_GAP_MS - $(uptime_ms) ))
        if [ "$wait_ms" -gt 0 ]; then
            sleep "$(( wait_ms / 1000 )).$(printf '%03d' $(( wait_ms % 1000 )))"
        fi
    fi
}

press_function_key() {
    hold="$1"
    device="$(input_device_named "$FUNCTION_INPUT_NAME")" || return 1
    wait_function_gap
    hold_key "$device" "$FUNCTION_SCAN_CODE" "$hold" || return 1
    function_last_ms="$(uptime_ms)"
}

press_ai_key() {
    device="$(input_device_named "$AI_INPUT_NAME")" || return 1
    wait_function_gap
    hold_key "$device" "$AI_SCAN_CODE" "$AI_HOLD_SECONDS" || return 1
    function_last_ms="$(uptime_ms)"
}

set_wifi_enabled() {
    if [ "$1" = "1" ]; then
        for attempt in 1 2 3; do
            svc wifi enable >/dev/null 2>&1 || true
            cmd wifi set-wifi-enabled enabled >/dev/null 2>&1 || true
            sleep 1
            if wifi_is_enabled; then
                return 0
            fi
        done
    else
        for attempt in 1 2 3 4 5; do
            svc wifi disable >/dev/null 2>&1 || true
            cmd wifi set-wifi-enabled disabled >/dev/null 2>&1 || true
            sleep 1
            if ! wifi_is_enabled; then
                return 0
            fi
        done
    fi
    return 1
}

disable_wifi_scan() {
    # Turn off always-on Wi-Fi scanning so the radio stays fully quiet.
    settings put global wifi_scan_always_enabled 0 >/dev/null 2>&1 || true
}

schedule_wifi_disable() {
    request="$1"
    now="$2"
    echo "scheduled wifi_disable $request $now delay=${WIFI_DISABLE_DELAY_SECONDS}s" > "$RESPONSE"
    log_msg "wifi disable scheduled request=$request delay=${WIFI_DISABLE_DELAY_SECONDS}s"
    # Let the wireless ADB client close before Wi-Fi drops; Rokid firmware can
    # wedge system_server if the radio disappears while adbwifi is mid-teardown.
    (
        sleep "$WIFI_DISABLE_DELAY_SECONDS"
        disable_wifi_scan
        if set_wifi_enabled 0; then
            log_msg "wifi disable ok request=$request"
        else
            log_msg "wifi disable failed request=$request"
        fi
    ) >/dev/null 2>&1 &
}

wifi_is_enabled() {
    if cmd wifi status 2>/dev/null | grep -qi "Wifi is enabled"; then
        return 0
    fi
    [ "$(settings get global wifi_on 2>/dev/null)" = "1" ]
}

handle_request() {
    request="$1"
    now="$2"
    command="${request%%:*}"
    if [ "$command" = "$request" ]; then
        command="shortcut"
    fi
    case "$command" in
        shortcut)
            if trigger_shortcut; then
                echo "ok shortcut $request $now" > "$RESPONSE"
                log_msg "trigger ok request=$request"
            else
                echo "failed shortcut $request $now" > "$RESPONSE"
                log_msg "trigger failed request=$request"
            fi
            ;;
        wifi_enable)
            if set_wifi_enabled 1; then
                echo "ok wifi_enable $request $now" > "$RESPONSE"
                log_msg "wifi enable ok request=$request"
            else
                echo "failed wifi_enable $request $now" > "$RESPONSE"
                log_msg "wifi enable failed request=$request"
            fi
            ;;
        wifi_disable)
            schedule_wifi_disable "$request" "$now"
            ;;
        function_key_short|function_key_long)
            if [ "$command" = "function_key_long" ]; then
                hold="$FUNCTION_LONG_HOLD_SECONDS"
            else
                hold="$FUNCTION_SHORT_HOLD_SECONDS"
            fi
            if press_function_key "$hold"; then
                echo "ok $command $request $now" > "$RESPONSE"
                log_msg "$command ok request=$request"
            else
                echo "failed $command $request $now" > "$RESPONSE"
                log_msg "$command failed request=$request"
            fi
            ;;
        ai_key)
            if press_ai_key; then
                echo "ok ai_key $request $now" > "$RESPONSE"
                log_msg "ai_key ok request=$request"
            else
                echo "failed ai_key $request $now" > "$RESPONSE"
                log_msg "ai_key failed request=$request"
            fi
            ;;
        *)
            echo "ignored $request $now" > "$RESPONSE"
            log_msg "ignored request=$request"
            ;;
    esac
}

check_and_dispatch() {
    # Two starters racing (R08 and the Nexus watchdog) can each spawn a loop. The one not
    # named in the PID file leaves before it can consume a request and inject it twice.
    owner="$(read_pid)"
    if [ -n "$owner" ] && [ "$owner" != "$$" ] && is_alive "$owner"; then
        log_msg "duplicate helper pid=$$ exits for pid=$owner"
        exit 0
    fi
    now="$(date +%s)"
    echo "$now" > "$HEARTBEAT"
    publish_entry_capability
    check_entry_request
    current="$(cat "$REQUEST" 2>/dev/null)"
    if [ -n "$current" ] && [ "$current" != "$last" ]; then
        last="$current"
        handle_request "$current" "$now"
    fi
}

prepare_doorbell() {
    if [ -e "$DOORBELL" ] && [ ! -p "$DOORBELL" ]; then
        log_msg "doorbell unavailable not_fifo path=$DOORBELL"
        return 1
    fi
    if [ ! -p "$DOORBELL" ]; then
        if ! mkfifo "$DOORBELL" >/dev/null 2>&1; then
            log_msg "doorbell mkfifo failed path=$DOORBELL"
            return 1
        fi
    fi
    chmod 666 "$DOORBELL" >/dev/null 2>&1 || true
    if ! exec 3<> "$DOORBELL"; then
        log_msg "doorbell open failed path=$DOORBELL"
        return 1
    fi
    read -t 0 _ <&3 >/dev/null 2>&1
    read_status="$?"
    # Android mksh reports read -t timeout as 142 (SIGALRM), not 1.
    case "$read_status" in
        0|1|142)
            return 0
            ;;
        *)
            log_msg "doorbell read probe failed status=$read_status"
            exec 3<&-
            return 1
            ;;
    esac
}

run_poll_loop() {
    last="$(cat "$REQUEST" 2>/dev/null)"
    log_msg "poll loop interval=${POLL_SECONDS}s"
    while true; do
        check_and_dispatch
        sleep "$POLL_SECONDS"
    done
}

run_event_loop() {
    # Existing requests belong to the previous helper session, just as in poll mode.
    last="$(cat "$REQUEST" 2>/dev/null)"
    log_msg "doorbell loop path=$DOORBELL idle_recheck=${IDLE_RECHECK_SECONDS}s"
    check_and_dispatch
    read_errors=0
    while true; do
        read -t "$IDLE_RECHECK_SECONDS" _ <&3 >/dev/null 2>&1
        read_status="$?"
        # Android mksh reports read -t timeout as 142 (SIGALRM), not 1.
        case "$read_status" in
            0|1|142)
                read_errors=0
                ;;
            *)
                # A background child exiting, such as the delayed Wi-Fi switch-off, interrupts
                # read with status 2. Only repeated failures mean the doorbell is broken.
                read_errors=$((read_errors + 1))
                [ "$read_errors" -lt 5 ] && continue
                log_msg "doorbell read failed status=$read_status; falling back to poll"
                exec 3<&-
                run_poll_loop
                ;;
        esac
        check_and_dispatch
    done
}

run_loop() {
    mkdir -p "$BASE"
    echo "$$" > "$PIDFILE"
    entry_session="$$-$(uptime_ms)"
    entry_last=""
    function_last_ms=""
    held_device=""
    trap 'release_held_key; exit 0' TERM INT
    echo "$FEATURE_LIST" > "$FEATURES"
    publish_entry_capability
    log_msg "run pid=$$ base=$BASE input=$INPUT_DEVICE code=$SETTINGS_SCAN_CODE"
    if prepare_doorbell; then
        run_event_loop
    fi
    run_poll_loop
}

start_bridge() {
    mkdir -p "$BASE"
    pid="$(read_pid)"
    if is_alive "$pid"; then
        read -r running_version running_session running_time < "$ENTRY_CAPABILITY" 2>/dev/null
        if [ "$running_version" = "$ENTRY_VERSION" ] \
                && [ "$(cat "$FEATURES" 2>/dev/null)" = "$FEATURE_LIST" ]; then
            case "$running_session" in
                "$pid"-*) echo "already running pid=$pid"; exit 0 ;;
            esac
        fi
        # Upgrade only the verified helper, preserving the existing ADB transport.
        kill "$pid" || exit 1
        for attempt in 1 2 3 4 5 6 7 8 9 10; do
            is_alive "$pid" || break
            sleep 0.1
        done
        if is_alive "$pid"; then
            echo "old bridge did not stop"
            exit 1
        fi
    fi
    rm -f "$ENTRY_CAPABILITY" "$FEATURES"
    rm -f "$PIDFILE"
    nohup sh "$0" run >/dev/null 2>&1 &
    sleep 0.3
    pid="$(read_pid)"
    if is_alive "$pid"; then
        echo "started pid=$pid base=$BASE"
    else
        echo "failed to start"
        exit 1
    fi
}

stop_bridge() {
    pid="$(read_pid)"
    if is_alive "$pid"; then
        kill "$pid"
        rm -f "$ENTRY_CAPABILITY" "$FEATURES"
        rm -f "$PIDFILE"
        echo "stopped pid=$pid"
    else
        rm -f "$PIDFILE"
        echo "not running"
    fi
}

status_bridge() {
    pid="$(read_pid)"
    if is_alive "$pid"; then
        echo "running pid=$pid base=$BASE heartbeat=$(cat "$HEARTBEAT" 2>/dev/null)"
    else
        echo "not running base=$BASE"
        exit 1
    fi
}

case "$1" in
    run)
        run_loop
        ;;
    start|"")
        start_bridge
        ;;
    stop)
        stop_bridge
        ;;
    restart)
        stop_bridge >/dev/null 2>&1
        start_bridge
        ;;
    status)
        status_bridge
        ;;
    trigger)
        trigger_shortcut
        ;;
    wifi-enable)
        set_wifi_enabled 1
        ;;
    wifi-disable)
        disable_wifi_scan
        set_wifi_enabled 0
        ;;
    *)
        echo "usage: $0 [start|stop|restart|status|trigger|wifi-enable|wifi-disable]"
        exit 2
        ;;
esac
