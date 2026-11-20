#!/usr/bin/env bash
# Serves the demo on http://localhost:8000 (camera + WebCrypto need localhost, not file://)
cd "$(dirname "$0")"
PORT="${1:-8000}"
echo "Zero-Trust Video Verification demo → http://localhost:$PORT"
( sleep 1; open "http://localhost:$PORT" 2>/dev/null || xdg-open "http://localhost:$PORT" 2>/dev/null ) &
exec python3 -m http.server "$PORT" --bind 127.0.0.1
