# RingMASTER Wi-Fi scan for Linux, after prelude.sh. Used on this computer and inside a guest. Read-only: it asks the
# adapter for a scan and reads what the system knows. It never connects, disconnects or reads a saved password.
# $1: the most seconds one scan may take (default 20).
BUDGET=${1:-20}
printf 'RINGMASTER-RADIO 1 wifi %s\n' "$N"
tools_rec
rfkill_recs
wifi_ifaces
if [ "$RF" = 0 ] && have rfkill; then sec rfkill-list 4096 t 3 rfkill list; fi
if [ -z "$WIFI_IFS" ]; then rec done; exit 0; fi
# NetworkManager first: it needs no root, and it already knows the security of each network.
NM=
if have nmcli && [ "$(t 3 nmcli -t -f RUNNING general 2>/dev/null)" = running ]; then NM=1; fi
if [ -n "$NM" ]; then
    sec nm-devices 8192 t 4 nmcli -t --escape yes -f DEVICE,TYPE,STATE,CONNECTION device status
    BW=; if t 4 nmcli -t -f BANDWIDTH device wifi list --rescan no >/dev/null 2>&1; then BW=,BANDWIDTH; fi
    sec nm-list 131072 t "$BUDGET" nmcli -t --escape yes -f "IN-USE,BSSID,SSID,MODE,CHAN,FREQ,RATE,SIGNAL,SECURITY,DEVICE$BW" device wifi list --rescan yes
fi
# iw: the link of each interface, and the scan result (a fresh scan only as root without NetworkManager; otherwise
# the last one the adapter kept).
if have iw; then
    sec iw-dev 16384 t 3 iw dev
    for i in $WIFI_IFS; do
        sec "iw-link $i" 4096 t 3 iw dev "$i" link
        if [ -z "$NM" ] && [ "$(id -u 2>/dev/null)" = 0 ]; then
            sec "iw-scan $i" 262144 t "$BUDGET" iw dev "$i" scan
        else
            sec "iw-scan-dump $i" 262144 t 5 iw dev "$i" scan dump
        fi
    done
fi
if [ -r "$PROC/net/wireless" ]; then sec proc-wireless 4096 cat "$PROC/net/wireless"; fi
rec done
