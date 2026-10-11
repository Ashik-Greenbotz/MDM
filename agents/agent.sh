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

while true; do
  UPTIME=$(cut -d. -f1 /proc/uptime)
  RAM=$(awk '/MemTotal/{t=$2}/MemAvailable/{a=$2}END{printf "%d", (t-a)*100/t}' /proc/meminfo)
  DISK=$(df -P / | awk 'NR==2{gsub("%","",$5); print $5}')
  # CPU busy % over one second
  CPU=$(awk '{u=$2+$4; t=$2+$4+$5; print u, t}' /proc/stat | head -1; sleep 1; awk '{u=$2+$4; t=$2+$4+$5; print u, t}' /proc/stat | head -1)
  CPU=$(echo "$CPU" | awk 'NR==1{u=$1;t=$2} NR==2{d=$2-t; if (d>0) printf "%d", ($1-u)*100/d; else print 0}')
  TEMP=$(awk '{printf "%.1f", $1/1000}' /sys/class/thermal/thermal_zone0/temp 2>/dev/null)
  IP=$(hostname -I 2>/dev/null | awk '{print $1}')
  BODY=$(printf '{"id":"%s","type":"linux","deviceName":"%s","model":"%s","os":"%s","localIp":"%s","uptime":%s,"cpu":%s,"ram":%s,"disk":%s%s}' \
    "$ID" "$(hostname)" "$MODEL" "$OS" "$IP" "$UPTIME" "${CPU:-0}" "${RAM:-0}" "${DISK:-0}" "${TEMP:+,\"temp\":$TEMP}")
  REPLY=$(curl -s -m 10 -X POST "$SERVER/api/report" -H "X-Device-Key: $KEY" -H "Content-Type: application/json" -d "$BODY")
  case "$REPLY" in *'"reboot"'*) reboot ;; esac
  sleep "$EVERY"
done
