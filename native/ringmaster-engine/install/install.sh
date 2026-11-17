#!/bin/sh
# RingMASTER Engine installer. POSIX sh; run as root inside the guest or on the node.
#
#   sh install.sh [--yes] [--sha256 HEX] [--port N] [--allow lan|any|NET,NET] [--new-token]
#
# It says what it will do, asks (unless --yes), checks the program's SHA-256, then installs:
#   /usr/local/lib/ringmaster-engine/   the program and this uninstaller
#   /etc/ringmaster-engine/             settings, certificate, key, token hash, the list of what was made
#   a service (systemd or OpenRC) that runs it as the account ringmaster-engine
# Run again with a newer bundle to upgrade: the key, certificate and token are kept, so the pairing stays.
# The program is taken from beside this file: ringmaster-engine-x86_64 or ringmaster-engine-aarch64.
# --sha256 is the hash the app gave you; without it the SHA256SUMS file beside the program is used.
set -eu

NAME=ringmaster-engine
LIB=/usr/local/lib/$NAME
CONF=/etc/$NAME
STATE=$CONF/installed
UNIT=/etc/systemd/system/$NAME.service
OPENRC=/etc/init.d/$NAME
HERE=$(cd "$(dirname "$0")" && pwd)

yes=0; want=; port=; allow=; new_token=0
while [ $# -gt 0 ]; do
    case "$1" in
        --yes) yes=1 ;;
        --sha256) want=$2; shift ;;
        --port) port=$2; shift ;;
        --allow) allow=$2; shift ;;
        --new-token) new_token=1 ;;
        *) echo "Unknown option: $1" >&2; exit 2 ;;
    esac
    shift
done

say() { printf '%s\n' "$*"; }
die() { printf 'RingMASTER Engine was not installed: %s\n' "$*" >&2; exit 1; }
have() { command -v "$1" >/dev/null 2>&1; }

[ "$(id -u)" = 0 ] || die "run this as root (for example with sudo)."

case "$(uname -m)" in
    x86_64 | amd64) arch=x86_64 ;;
    aarch64 | arm64) arch=aarch64 ;;
    *) die "this system is $(uname -m); the Engine is built for x86_64 and aarch64." ;;
esac
bin=$HERE/$NAME-$arch
[ -f "$bin" ] || die "$NAME-$arch is not beside this installer."

sha() {
    if have sha256sum; then sha256sum "$1" | cut -d' ' -f1
    elif have busybox; then busybox sha256sum "$1" | cut -d' ' -f1
    elif have openssl; then openssl dgst -sha256 -r "$1" | cut -d' ' -f1
    elif have shasum; then shasum -a 256 "$1" | cut -d' ' -f1
    else die "this system has no SHA-256 tool (sha256sum, busybox, openssl or shasum)."
    fi
}
if [ -z "$want" ]; then
    [ -f "$HERE/SHA256SUMS" ] || die "give --sha256 (the app shows it) or put SHA256SUMS beside the program."
    want=$(awk -v f="$NAME-$arch" '$2 == f || $2 == "*" f {print $1}' "$HERE/SHA256SUMS")
    source_of_hash="SHA256SUMS beside it"
else
    source_of_hash="the hash you gave"
fi
case "$want" in *[!0-9a-fA-F]* | '') die "the expected SHA-256 is not 64 hex digits." ;; esac
[ ${#want} = 64 ] || die "the expected SHA-256 is not 64 hex digits."
got=$(sha "$bin")
[ "$got" = "$(printf '%s' "$want" | tr 'A-F' 'a-f')" ] || die "the program's SHA-256 is $got, not the expected $want. Nothing was changed."

if [ -d /run/systemd/system ] && have systemctl; then init=systemd
elif have openrc-run || [ -x /sbin/openrc-run ]; then init=openrc
else init=none
fi

upgrade=0; [ -f "$CONF/key.pk8" ] && upgrade=1
version=$("$bin" version 2>/dev/null | cut -d' ' -f2) || die "the program does not run on this system."
[ -n "$port" ] || port=$(sed -n 's/^port=//p' "$CONF/engine.conf" 2>/dev/null || true)
[ -n "$port" ] || port=8696
[ -n "$allow" ] || allow=$(sed -n 's/^allow=//p' "$CONF/engine.conf" 2>/dev/null || true)
[ -n "$allow" ] || allow=lan
case "$port" in *[!0-9]* | '') die "the port is not a number." ;; esac

say "RingMASTER Engine $version for $arch (SHA-256 checked against $source_of_hash)."
if [ "$upgrade" = 1 ]; then
    say "This upgrades the Engine already installed here. Its key, certificate and token are kept."
else
    say "This will:"
    say "  - create the system account $NAME (no home, no login shell)"
    say "  - put the program in $LIB/"
    say "  - create $CONF/ with a new certificate, key and pairing token"
fi
case "$init" in
    systemd) say "  - install and start the systemd service $NAME" ;;
    openrc) say "  - install and start the OpenRC service $NAME" ;;
    none) say "  - start nothing: this system has no service manager the installer knows; it prints the start command" ;;
esac
say "  - listen on TCP port $port, answering only $( [ "$allow" = lan ] && echo 'local networks and tailnets' || echo "$allow")"
say "The Engine only reads: which Bluetooth and Wi-Fi radios this system has, and scans when RingMASTER asks."
say "It runs no commands it is sent, changes nothing and never reads a saved Wi-Fi password."
if [ "$yes" != 1 ]; then
    if [ -t 0 ]; then
        printf 'Continue? [y/N] '
        read -r answer
        case "$answer" in y | Y | yes | YES) ;; *) say "Nothing was changed."; exit 0 ;; esac
    else
        die "add --yes to install without a question."
    fi
fi

made_user=0; made_group=0
[ -f "$STATE" ] && grep -q '^made_user=1' "$STATE" && made_user=1
[ -f "$STATE" ] && grep -q '^made_group=1' "$STATE" && made_group=1
nologin=/usr/sbin/nologin; [ -x "$nologin" ] || nologin=/sbin/nologin; [ -x "$nologin" ] || nologin=/bin/false
if ! grep -q "^$NAME:" /etc/group; then
    if have groupadd; then groupadd --system "$NAME"; else addgroup -S "$NAME"; fi
    made_group=1
fi
if ! grep -q "^$NAME:" /etc/passwd; then
    if have useradd; then useradd --system --no-create-home --home-dir /nonexistent --shell "$nologin" --gid "$NAME" "$NAME"
    else adduser -S -D -H -h /nonexistent -s "$nologin" -G "$NAME" "$NAME"
    fi
    made_user=1
fi

stop_service() {
    case "$init" in
        systemd) systemctl stop "$NAME" 2>/dev/null || true ;;
        openrc) rc-service "$NAME" stop 2>/dev/null || true ;;
    esac
}
[ "$upgrade" = 1 ] && stop_service

mkdir -p "$LIB"
chmod 0755 "$LIB"
cp "$bin" "$LIB/$NAME.new" && chmod 0755 "$LIB/$NAME.new" && mv -f "$LIB/$NAME.new" "$LIB/$NAME"
cp "$HERE/uninstall.sh" "$LIB/uninstall.sh" && chmod 0755 "$LIB/uninstall.sh"
chown -R root:root "$LIB"

mkdir -p "$CONF"
chown root:"$NAME" "$CONF"
chmod 0750 "$CONF"
printf '# RingMASTER Engine settings. allow: lan, any, or networks such as 192.0.2.0/24,2001:db8::/32\nport=%s\nallow=%s\n' "$port" "$allow" > "$CONF/engine.conf"
chmod 0644 "$CONF/engine.conf"
token=
if [ "$upgrade" = 0 ]; then
    out=$("$LIB/$NAME" init --dir "$CONF")
    token=$(printf '%s\n' "$out" | sed -n 's/^TOKEN=//p')
elif [ "$new_token" = 1 ]; then
    out=$("$LIB/$NAME" new-token --dir "$CONF")
    token=$(printf '%s\n' "$out" | sed -n 's/^TOKEN=//p')
fi
# The service reads its key and token hash; it cannot change them.
chown root:"$NAME" "$CONF/key.pk8" "$CONF/token.sha256"
chmod 0440 "$CONF/key.pk8" "$CONF/token.sha256"
chown root:root "$CONF/cert.der"
chmod 0444 "$CONF/cert.der"
fingerprint=$("$LIB/$NAME" fingerprint --dir "$CONF")

case "$init" in
systemd)
    cat > "$UNIT" <<EOF
[Unit]
Description=RingMASTER Engine (read-only radio reports for RingMASTER)
After=network.target

[Service]
Type=simple
User=$NAME
Group=$NAME
ExecStart=$LIB/$NAME serve --dir $CONF
Restart=on-failure
RestartSec=5
NoNewPrivileges=yes
CapabilityBoundingSet=
AmbientCapabilities=
ProtectSystem=strict
ProtectHome=yes
PrivateTmp=yes
RestrictAddressFamilies=AF_UNIX AF_INET AF_INET6 AF_NETLINK
ProtectKernelTunables=yes
ProtectKernelModules=yes
ProtectKernelLogs=yes
ProtectControlGroups=yes
ProtectClock=yes
ProtectHostname=yes
RestrictNamespaces=yes
LockPersonality=yes
MemoryDenyWriteExecute=yes
RestrictRealtime=yes
RestrictSUIDSGID=yes
SystemCallArchitectures=native
SystemCallFilter=@system-service
UMask=0077

[Install]
WantedBy=multi-user.target
EOF
    chmod 0644 "$UNIT"
    systemctl daemon-reload
    systemctl enable "$NAME" >/dev/null 2>&1
    systemctl restart "$NAME"
    ;;
openrc)
    cat > "$OPENRC" <<EOF
#!/sbin/openrc-run
description="RingMASTER Engine (read-only radio reports for RingMASTER)"
supervisor=supervise-daemon
command="$LIB/$NAME"
command_args="serve --dir $CONF"
command_user="$NAME:$NAME"
output_log="/var/log/$NAME.log"
error_log="/var/log/$NAME.log"
depend() { need net; }
start_pre() { checkpath -f -o $NAME:$NAME -m 0640 /var/log/$NAME.log; }
EOF
    chmod 0755 "$OPENRC"
    rc-update add "$NAME" default >/dev/null 2>&1
    rc-service "$NAME" restart >/dev/null 2>&1 || rc-service "$NAME" start
    ;;
esac

listening=0
if [ "$init" != none ]; then
    i=0
    while [ $i -lt 10 ]; do
        if "$LIB/$NAME" status --dir "$CONF" >/dev/null 2>&1; then listening=1; break; fi
        # An unprivileged container without nesting cannot set up systemd's sandbox (status 226): relax it once.
        if [ "$init" = systemd ] && [ $i = 2 ] && [ "$(systemctl show -p ExecMainStatus --value "$NAME" 2>/dev/null)" = 226 ]; then
            mkdir -p "$UNIT.d"
            printf '[Service]\nProtectSystem=no\nProtectHome=no\nPrivateTmp=no\nProtectKernelTunables=no\nProtectKernelModules=no\nProtectKernelLogs=no\nProtectControlGroups=no\nProtectClock=no\nProtectHostname=no\nRestrictNamespaces=no\n' > "$UNIT.d/container.conf"
            systemctl daemon-reload
            systemctl restart "$NAME"
            say "This container cannot sandbox services; the Engine runs without systemd's file-system sandbox."
        fi
        sleep 1
        i=$((i + 1))
    done
fi

{
    echo "version=$version"
    echo "init=$init"
    echo "made_user=$made_user"
    echo "made_group=$made_group"
    echo "path=$LIB"
    echo "path=$CONF"
    [ "$init" = systemd ] && echo "path=$UNIT" && echo "path=$UNIT.d"
    [ "$init" = openrc ] && echo "path=$OPENRC" && echo "path=/var/log/$NAME.log"
    true
} > "$STATE"
chmod 0644 "$STATE"

say ""
if [ "$init" = none ]; then
    say "Installed. Start it with:  su -s /bin/sh $NAME -c '$LIB/$NAME serve --dir $CONF'"
elif [ "$listening" = 1 ]; then
    say "RingMASTER Engine $version is running on port $port."
else
    say "Installed, but the service did not start listening. See: $( [ "$init" = systemd ] && echo "journalctl -u $NAME" || echo "/var/log/$NAME.log")"
fi
if have ufw && ufw status 2>/dev/null | grep -q '^Status: active'; then
    say "ufw is active here. To let RingMASTER in:  ufw allow $port/tcp"
fi
if have firewall-cmd && firewall-cmd --state >/dev/null 2>&1; then
    say "firewalld is running here. To let RingMASTER in:  firewall-cmd --permanent --add-port=$port/tcp && firewall-cmd --reload"
fi
say "Certificate SHA-256 (RingMASTER shows the same when you pair):"
say "  $fingerprint"
if [ -n "$token" ]; then
    say "Pairing token, shown this once (enter it in RingMASTER):"
    say "  $token"
    say "RINGMASTER-ENGINE-PAIRING version=$version port=$port sha256=$fingerprint token=$token"
else
    say "The pairing token is unchanged. To make a new one: sh $HERE/install.sh --new-token"
    say "RINGMASTER-ENGINE-READY version=$version port=$port sha256=$fingerprint"
fi
[ "$init" = none ] || [ "$listening" = 1 ] || exit 1
