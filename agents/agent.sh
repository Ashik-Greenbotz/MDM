#!/bin/sh
# Linux device agent: checks in with the device portal so this machine shows up there.
# Run:  SERVER=http://192.168.68.50:8080 KEY=change-me-device-key sh agent.sh
# Run at boot (systemd): see the README. Needs curl. Run as root for the portal's Restart command.
SERVER="${SERVER:-http://192.168.68.50:8080}"
KEY="${KEY:-change-me-device-key}"
EVERY="${EVERY:-30}"
ID="linux-$(cat /etc/machine-id 2>/dev/null || hostname)"
OS="$(. /etc/os-release 2>/dev/null; echo "${PRETTY_NAME:-Linux}")"
MODEL="$(cat /sys/devices/virtual/dmi/id/product_name 2>/dev/null || cat /proc/device-tree/model 2>/dev/null | tr -d '\0' || uname -m)"

# WireGuard VPN from the portal's reply: "vpn":{"on":true,"address":…,"privateKey":…,"hubPublicKey":…,"endpoint":…,"allowed":…}
# Runs as wg-quick@mdm-vpn (starts at boot). Only 10.66.0.0/16 goes through it. Needs root (the systemd service runs as root).
WGCONF=/etc/wireguard/mdm-vpn.conf
vpn_get() { echo "$VPN" | sed -n "s/.*\"$1\":\"\{0,1\}\([^\",}]*\).*/\1/p"; }
update_vpn() {
  VPN=$(echo "$1" | sed -n 's/.*"vpn":{\([^}]*\)}.*/\1/p')
  case "$VPN" in
    *'"on":true'*) ;;
    *) [ -f "$WGCONF" ] && { systemctl disable --now wg-quick@mdm-vpn 2>/dev/null || wg-quick down mdm-vpn 2>/dev/null; rm -f "$WGCONF"; }; return ;;
  esac
  command -v wg-quick >/dev/null || { apt-get install -y wireguard-tools >/dev/null 2>&1 || dnf install -y wireguard-tools >/dev/null 2>&1; } || return
  NEW=$(printf '[Interface]\nPrivateKey = %s\nAddress = %s\n\n[Peer]\nPublicKey = %s\nEndpoint = %s\nAllowedIPs = %s\nPersistentKeepalive = 25\n' \
    "$(vpn_get privateKey)" "$(vpn_get address)" "$(vpn_get hubPublicKey)" "$(vpn_get endpoint)" "$(vpn_get allowed)")
  [ "$NEW" = "$(cat "$WGCONF" 2>/dev/null)" ] && ip link show mdm-vpn >/dev/null 2>&1 && return
  mkdir -p /etc/wireguard && umask 077 && printf '%s\n' "$NEW" > "$WGCONF"
  wg-quick down mdm-vpn 2>/dev/null; systemctl enable wg-quick@mdm-vpn 2>/dev/null; wg-quick up mdm-vpn >/dev/null 2>&1 || systemctl restart wg-quick@mdm-vpn
}
# up = the hub answered within the last 3 minutes
vpn_up() { HS=$(wg show mdm-vpn latest-handshakes 2>/dev/null | awk '{print $2}'); [ -n "$HS" ] && [ "$HS" -gt 0 ] && [ $(( $(date +%s) - HS )) -lt 180 ] && echo true || echo false; }

while true; do
  UPTIME=$(cut -d. -f1 /proc/uptime)
  RAM=$(awk '/MemTotal/{t=$2}/MemAvailable/{a=$2}END{printf "%d", (t-a)*100/t}' /proc/meminfo)
  DISK=$(df -P / | awk 'NR==2{gsub("%","",$5); print $5}')
  # CPU busy % over one second
  CPU=$(awk '{u=$2+$4; t=$2+$4+$5; print u, t}' /proc/stat | head -1; sleep 1; awk '{u=$2+$4; t=$2+$4+$5; print u, t}' /proc/stat | head -1)
  CPU=$(echo "$CPU" | awk 'NR==1{u=$1;t=$2} NR==2{d=$2-t; if (d>0) printf "%d", ($1-u)*100/d; else print 0}')
  TEMP=$(awk '{printf "%.1f", $1/1000}' /sys/class/thermal/thermal_zone0/temp 2>/dev/null)
  # source address of the default route = the real LAN (not docker0 / VPN); hostname -I as fallback
  IP=$(ip -4 route get 1.1.1.1 2>/dev/null | awk '{for (i = 1; i < NF; i++) if ($i == "src") print $(i + 1)}')
  [ -n "$IP" ] || IP=$(hostname -I 2>/dev/null | awk '{print $1}')
  BODY=$(printf '{"id":"%s","type":"linux","deviceName":"%s","model":"%s","os":"%s","localIp":"%s","uptime":%s,"cpu":%s,"ram":%s,"disk":%s,"vpnUp":%s%s}' \
    "$ID" "$(hostname)" "$MODEL" "$OS" "$IP" "$UPTIME" "${CPU:-0}" "${RAM:-0}" "${DISK:-0}" "$(vpn_up)" "${TEMP:+,\"temp\":$TEMP}")
  REPLY=$(curl -s -m 10 -X POST "$SERVER/api/report" -H "X-Device-Key: $KEY" -H "Content-Type: application/json" -d "$BODY")
  case "$REPLY" in *'"reboot"'*) reboot ;; esac
  update_vpn "$REPLY"
  sleep "$EVERY"
done
