# RingMASTER Bluetooth scan for the RingMASTER Engine, after prelude.sh. Read-only: discovery through BlueZ, then the
# objects BlueZ knows. It never pairs, connects or changes a radio.
# It is the guest agent's scan (Bluez.kt, bluezScanScript) written out with the seconds as $1, so it prints the same
# RINGMASTER-BLUEZ markers and parseBluezScanOutput reads both. Change the two together.
# $1: the seconds discovery runs (3 to 30, default 10).
seconds=${1:-10}
case "$seconds" in '' | *[!0-9]*) seconds=10 ;; esac
[ "$seconds" -lt 3 ] && seconds=3
[ "$seconds" -gt 30 ] && seconds=30
trap 'printf "%s\n" "RINGMASTER-BLUEZ:EARLY"; exit 0' TERM INT
if ! command -v bluetoothctl >/dev/null 2>&1 || ! command -v busctl >/dev/null 2>&1; then
    printf '%s\n' 'RINGMASTER-BLUEZ:MISSING'
    exit 0
fi
if [ -z "$(ls -A /sys/class/bluetooth 2>/dev/null)" ]; then
    printf '%s\n' 'RINGMASTER-BLUEZ:NOADAPTER'
    exit 0
fi
bt_limited() {
    if command -v timeout >/dev/null 2>&1; then
        timeout 3 "$@"
    else
        "$@"
    fi
}
powered=
for adapter in $(bt_limited bluetoothctl list 2>/dev/null | awk '/^Controller / {print $2}'); do
    if bt_limited bluetoothctl show "$adapter" 2>/dev/null | grep -q 'Powered: yes'; then
        powered=$adapter
        break
    fi
done
if [ -n "$powered" ]; then
    {
        printf 'select %s\nscan on\n' "$powered"
        sleep "$seconds"
        printf 'quit\n'
    } | bluetoothctl --timeout "$seconds" >/dev/null 2>&1
fi
if objects=$(busctl --json=short call org.bluez / org.freedesktop.DBus.ObjectManager GetManagedObjects 2>/dev/null); then
    printf '%s\n' 'RINGMASTER-BLUEZ:OBJECTS' "$objects"
else
    printf '%s\n' 'RINGMASTER-BLUEZ:NODAEMON'
fi
