# RingMASTER radio scripts, shared start. Read-only: nothing here writes a file, connects, disconnects or changes a radio.
#
# Output is one header line, then lines that begin with a random token and a tab. The token is made here, so text that a
# nearby device chose (a network name, a Bluetooth name) cannot pass for a marker. Every command is optional and runs
# under timeout when the system has it. Every size is capped.
#
#   <token> TAB <kind> TAB key=value TAB key=value ...    a record
#   <token> TAB begin TAB <name>                          raw tool output follows
#   <token> TAB end TAB <name>
#   <token> TAB done [TAB early]                          the script reached its end (early: it was stopped)
#
# RINGMASTER_SYS and RINGMASTER_PROC point the sysfs and procfs reads somewhere else for tests.
LC_ALL=C; export LC_ALL
PATH="$PATH:/usr/local/sbin:/usr/sbin:/sbin:/snap/bin"; export PATH
SYS=${RINGMASTER_SYS:-/sys}
PROC=${RINGMASTER_PROC:-/proc}
N=$(od -An -N8 -tx1 /dev/urandom 2>/dev/null | tr -d ' \n')
[ -n "$N" ] || N="r$$"
TO=$(command -v timeout 2>/dev/null)
have() { command -v "$1" >/dev/null 2>&1; }
# t SECONDS COMMAND...: the command under timeout when the system has it.
t() { s=$1; shift; if [ -n "$TO" ]; then "$TO" "$s" "$@"; else "$@"; fi; }
# clean: one line without control characters, at most 160 bytes.
clean() { tr -d '\000-\010\013-\037\177' | tr '\t\n' '  ' | head -c 160; }
# rd FILE: the first bytes of a small sysfs file, cleaned. Nothing when it cannot be read.
rd() { [ -r "$1" ] && head -c 200 "$1" 2>/dev/null | clean; }
# rec KIND KEY=VALUE...: one record line.
rec() { printf '%s\t%s' "$N" "$1"; shift; for a in "$@"; do printf '\t%s' "$a"; done; printf '\n'; }
# sec NAME MAXBYTES COMMAND...: the command's output (errors dropped) between a begin line and an end line.
sec() { n=$1; c=$2; shift 2; printf '%s\tbegin\t%s\n' "$N" "$n"; "$@" 2>/dev/null | tr -d '\000\r' | head -c "$c"; printf '\n%s\tend\t%s\n' "$N" "$n"; }
trap 'printf "\n%s\tdone\tearly\n" "$N"; exit 0' TERM INT

# radio_rec KIND ID SYSFS-DIR [KEY=VALUE...]: a record with what the kernel says about the device behind a class entry:
# the driver bound to it, the bus, the PCI address and ids, or the USB ids and names (read from the USB device that
# sits one level above an interface).
radio_rec() {
    k=$1; id=$2; dir=$3; shift 3
    dev=$(readlink -f "$dir/device" 2>/dev/null)
    drv=; bus=; pci=; vid=; pid=; prod=; mfr=
    if [ -n "$dev" ]; then
        if [ -L "$dev/driver" ]; then drv=$(readlink "$dev/driver" 2>/dev/null); drv=${drv##*/}; fi
        if [ -L "$dev/subsystem" ]; then bus=$(readlink "$dev/subsystem" 2>/dev/null); bus=${bus##*/}; fi
        case "${dev##*/}" in
            ????:??:??.?) pci=${dev##*/}; vid=$(rd "$dev/vendor"); pid=$(rd "$dev/device") ;;
        esac
        for u in "$dev" "$dev/.."; do
            if [ -r "$u/idVendor" ]; then vid=$(rd "$u/idVendor"); pid=$(rd "$u/idProduct"); prod=$(rd "$u/product"); mfr=$(rd "$u/manufacturer"); break; fi
        done
    fi
    rec "$k" "id=$id" "driver=$drv" "bus=$bus" "pci=$pci" "vendor=$vid" "device=$pid" "product=$prod" "maker=$mfr" "$@"
}

# One record per rfkill switch: name (phy0, hci0), type (wlan, bluetooth), soft and hard block (1 = blocked).
RF=0
rfkill_recs() {
    for d in "$SYS"/class/rfkill/rfkill*; do
        [ -e "$d" ] || continue
        RF=1
        rec rfkill "name=$(rd "$d/name")" "type=$(rd "$d/type")" "soft=$(rd "$d/soft")" "hard=$(rd "$d/hard")"
    done
}

# One record per wireless network interface (WIFI_IFS collects their names), then one per radio that has none.
WIFI_IFS=
SEEN_PHYS=
wifi_ifaces() {
    for d in "$SYS"/class/net/*; do
        { [ -e "$d/phy80211" ] || [ -d "$d/wireless" ]; } || continue
        i=${d##*/}
        phy=; if [ -L "$d/phy80211" ]; then phy=$(readlink "$d/phy80211" 2>/dev/null); phy=${phy##*/}; fi
        radio_rec wifi "$i" "$d" "phy=$phy" "mac=$(rd "$d/address")" "operstate=$(rd "$d/operstate")" "carrier=$(rd "$d/carrier")"
        WIFI_IFS="$WIFI_IFS $i"
        SEEN_PHYS="$SEEN_PHYS $phy"
    done
    for p in "$SYS"/class/ieee80211/phy*; do
        [ -e "$p" ] || continue
        ph=${p##*/}
        case " $SEEN_PHYS " in *" $ph "*) continue ;; esac
        radio_rec wifiphy "$ph" "$p" "phy=$ph"
    done
}

# One record per Bluetooth controller the kernel registered (connection entries such as hci0:256 are skipped).
bt_ctrls() {
    for d in "$SYS"/class/bluetooth/hci*; do
        [ -e "$d" ] || continue
        i=${d##*/}
        case "$i" in *:*) continue ;; esac
        radio_rec bt "$i" "$d"
    done
}

# Which optional tools exist here (names only), and who runs the script (0 is root).
tools_rec() {
    TL=
    for x in nmcli iw rfkill bluetoothctl hciconfig hcitool btmgmt; do
        if have "$x"; then TL="$TL $x"; fi
    done
    rec tools $TL
    rec uid "$(id -u 2>/dev/null)"
}
