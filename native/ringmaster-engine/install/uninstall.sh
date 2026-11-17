#!/bin/sh
# RingMASTER Engine uninstaller. POSIX sh; run as root.
#
#   sh /usr/local/lib/ringmaster-engine/uninstall.sh [--yes]
#
# It says what it will remove, asks (unless --yes), stops and removes the service, the program, /etc/ringmaster-engine
# and the account the installer made, then checks that none of it is left.
set -eu

NAME=ringmaster-engine
LIB=/usr/local/lib/$NAME
CONF=/etc/$NAME
STATE=$CONF/installed
yes=0
[ "${1:-}" = --yes ] && yes=1

say() { printf '%s\n' "$*"; }
have() { command -v "$1" >/dev/null 2>&1; }
[ "$(id -u)" = 0 ] || { say "Run this as root (for example with sudo)." >&2; exit 1; }

init=; made_user=0; made_group=0; paths=
if [ -f "$STATE" ]; then
    init=$(sed -n 's/^init=//p' "$STATE")
    grep -q '^made_user=1' "$STATE" && made_user=1
    grep -q '^made_group=1' "$STATE" && made_group=1
    paths=$(sed -n 's/^path=//p' "$STATE")
fi
# Whatever the record says, these are the only places the installer writes.
for p in "$LIB" "$CONF" /etc/systemd/system/$NAME.service /etc/systemd/system/$NAME.service.d /etc/init.d/$NAME /var/log/$NAME.log; do
    case " $(echo $paths) " in *" $p "*) ;; *) paths="$paths $p" ;; esac
done

say "This removes the RingMASTER Engine:"
say "  - stops and removes its service"
for p in $paths; do [ -e "$p" ] && say "  - deletes $p"; done
[ "$made_user" = 1 ] && say "  - deletes the account $NAME"
[ "$made_group" = 1 ] && say "  - deletes the group $NAME"
say "RingMASTER then shows this guest as not paired."
if [ "$yes" != 1 ]; then
    if [ -t 0 ]; then
        printf 'Continue? [y/N] '
        read -r answer
        case "$answer" in y | Y | yes | YES) ;; *) say "Nothing was changed."; exit 0 ;; esac
    else
        say "Add --yes to remove it without a question." >&2
        exit 1
    fi
fi

if have systemctl && [ -d /run/systemd/system ]; then
    systemctl disable --now "$NAME" >/dev/null 2>&1 || true
fi
if have rc-service; then
    rc-service "$NAME" stop >/dev/null 2>&1 || true
    rc-update del "$NAME" default >/dev/null 2>&1 || true
fi
# A copy started by hand (a system without a service manager).
if have pkill; then pkill -f "$LIB/$NAME serve" 2>/dev/null || true; fi
for p in $paths; do
    case "$p" in "$LIB" | "$CONF" | /etc/systemd/system/$NAME.service* | /etc/init.d/$NAME | /var/log/$NAME.log) rm -rf "$p" ;; esac
done
if have systemctl && [ -d /run/systemd/system ]; then
    systemctl daemon-reload 2>/dev/null || true
    systemctl reset-failed "$NAME" 2>/dev/null || true
fi
if [ "$made_user" = 1 ] && grep -q "^$NAME:" /etc/passwd; then
    if have userdel; then userdel "$NAME"; else deluser "$NAME"; fi
fi
if [ "$made_group" = 1 ] && grep -q "^$NAME:" /etc/group; then
    if have groupdel; then groupdel "$NAME"; else delgroup "$NAME"; fi
fi

left=
for p in $paths; do [ -e "$p" ] && left="$left $p"; done
[ "$made_user" = 1 ] && grep -q "^$NAME:" /etc/passwd && left="$left account:$NAME"
[ "$made_group" = 1 ] && grep -q "^$NAME:" /etc/group && left="$left group:$NAME"
if have pgrep && pgrep -f "$LIB/$NAME serve" >/dev/null 2>&1; then left="$left process:$NAME"; fi
if [ -n "$left" ]; then
    say "Not everything could be removed:$left"
    exit 1
fi
say "The RingMASTER Engine is removed. Nothing of it is left."
say "RINGMASTER-ENGINE-REMOVED"
