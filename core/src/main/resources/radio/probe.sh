# RingMASTER guest radio probe, after prelude.sh. Read-only. Lists the Bluetooth controllers and the Wi-Fi interfaces
# this system itself sees: what the kernel registered (sysfs), rfkill blocks, and names, addresses and power from the
# tools that happen to be installed. It scans for nothing and connects to nothing.
printf 'RINGMASTER-RADIO 1 probe %s\n' "$N"
tools_rec
rfkill_recs
bt_ctrls
wifi_ifaces
if [ "$RF" = 0 ] && have rfkill; then sec rfkill-list 4096 t 3 rfkill list; fi
if have bluetoothctl; then
    list=$(t 3 bluetoothctl list 2>/dev/null | tr -d '\r')
    sec bluetoothctl-list 4096 printf '%s\n' "$list"
    for a in $(printf '%s\n' "$list" | awk '/Controller /{print $2}'); do
        case "$a" in
            ??:??:??:??:??:??) sec "bluetoothctl-show $a" 4096 t 3 bluetoothctl show "$a" ;;
        esac
    done
fi
if have hciconfig; then sec hciconfig 8192 t 3 hciconfig -a; fi
if have hcitool; then sec hcitool-dev 2048 t 3 hcitool dev; fi
if have btmgmt; then sec btmgmt-info 8192 t 3 btmgmt info; fi
if have iw; then
    for i in $WIFI_IFS; do sec "iw-link $i" 4096 t 3 iw dev "$i" link; done
fi
rec done
