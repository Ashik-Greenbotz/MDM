#!/usr/bin/env bash
# Starts the kiosk server and dashboard. Keep this terminal open while tablets are in use.
# Password and device key come from functions/.env (not in git; copy functions/.env.example to start).
set -e
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PORT="${PORT:-8080}"

if [ ! -f "$DIR/functions/.env" ]; then
  echo "Missing functions/.env - copy functions/.env.example to functions/.env and set a password."
  exit 1
fi

set -a
# shellcheck disable=SC1091
source "$DIR/functions/.env"
set +a

CLOUDFLARED_PID=
NODE_PID=
cleanup() {
  [ -n "$NODE_PID" ] && kill "$NODE_PID" 2>/dev/null || true
  [ -n "$CLOUDFLARED_PID" ] && kill "$CLOUDFLARED_PID" 2>/dev/null || true
}
trap cleanup EXIT INT TERM

# Free Cloudflare tunnel: prints a https://....trycloudflare.com address to use from outside
# the office. The address changes every time this starts.
if command -v cloudflared >/dev/null 2>&1; then
  echo "Starting Cloudflare tunnel (public https:// address, changes each run)..."
  cloudflared tunnel --url "http://localhost:$PORT" &
  CLOUDFLARED_PID=$!
else
  echo "cloudflared not found - skipping the public tunnel. Install it for a public https:// address."
fi

URL="http://localhost:$PORT"
if command -v open >/dev/null 2>&1; then
  open "$URL"            # macOS
elif command -v xdg-open >/dev/null 2>&1; then
  xdg-open "$URL" >/dev/null 2>&1 &   # Linux
else
  echo "Open $URL in your browser"
fi

cd "$DIR/functions"
node server.js &
NODE_PID=$!
wait "$NODE_PID"
