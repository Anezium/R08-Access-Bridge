#!/system/bin/sh

PKG="${R08_PACKAGE:-com.anezium.r08accessbridge}"
BASE="${R08_BRIDGE_DIR:-/sdcard/Android/data/$PKG/files/shortcut_bridge}"
REQUEST="$BASE/request"
RESPONSE="$BASE/response"
HEARTBEAT="$BASE/heartbeat"
PIDFILE="${R08_PIDFILE:-/data/local/tmp/r08-shortcut-bridge.pid}"
LOGFILE="${R08_LOGFILE:-/data/local/tmp/r08-shortcut-bridge.log}"
INPUT_DEVICE="${R08_INPUT_DEVICE:-/dev/input/event1}"
SETTINGS_SCAN_CODE="${R08_SETTINGS_SCAN_CODE:-149}"
POLL_SECONDS="${R08_POLL_SECONDS:-0.2}"

log_msg() {
    echo "$(date +%s) $*" >> "$LOGFILE"
}

is_alive() {
    pid="$1"
    [ -n "$pid" ] && [ -d "/proc/$pid" ]
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
            if set_wifi_enabled 0; then
                echo "ok wifi_disable $request $now" > "$RESPONSE"
                log_msg "wifi disable ok request=$request"
            else
                echo "failed wifi_disable $request $now" > "$RESPONSE"
                log_msg "wifi disable failed request=$request"
            fi
            ;;
        *)
            echo "ignored $request $now" > "$RESPONSE"
            log_msg "ignored request=$request"
            ;;
    esac
}

run_loop() {
    mkdir -p "$BASE"
    echo "$$" > "$PIDFILE"
    log_msg "run pid=$$ base=$BASE input=$INPUT_DEVICE code=$SETTINGS_SCAN_CODE"
    last="$(cat "$REQUEST" 2>/dev/null)"
    while true; do
        now="$(date +%s)"
        echo "$now" > "$HEARTBEAT"
        current="$(cat "$REQUEST" 2>/dev/null)"
        if [ -n "$current" ] && [ "$current" != "$last" ]; then
            last="$current"
            handle_request "$current" "$now"
        fi
        sleep "$POLL_SECONDS"
    done
}

start_bridge() {
    mkdir -p "$BASE"
    pid="$(read_pid)"
    if is_alive "$pid"; then
        echo "already running pid=$pid"
        exit 0
    fi
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
        set_wifi_enabled 0
        ;;
    *)
        echo "usage: $0 [start|stop|restart|status|trigger|wifi-enable|wifi-disable]"
        exit 2
        ;;
esac
