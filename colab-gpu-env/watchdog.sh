#!/usr/bin/env bash
# Keep the endpoint alive for the length of a Colab session.
#
#   - restarts llama_cpp.server + tunnel if either dies
#   - stops deliberately at ROTATE_AFTER (default 11h30m) so the ~12h session cap
#     arrives while nothing is mid-job, instead of killing a run mid-flight
set -uo pipefail

PORT="${PORT:-8000}"
LOG_DIR="${LOG_DIR:-/content/colab-gpu}"
ROTATE_AFTER="${ROTATE_AFTER:-41400}"   # seconds; 11h30m
CHECK_EVERY="${CHECK_EVERY:-30}"
START=$(date +%s)

mkdir -p "$LOG_DIR"

log() { echo "[$(date -u +%H:%M:%S)] $*" | tee -a "$LOG_DIR/watchdog.log"; }

key() { cat "$LOG_DIR/api_key.txt" 2>/dev/null || true; }

healthy() {
  curl -sf -H "Authorization: Bearer $(key)" "http://127.0.0.1:$PORT/v1/models" >/dev/null 2>&1
}

log "watchdog up (rotate after ${ROTATE_AFTER}s, check every ${CHECK_EVERY}s)"

while true; do
  now=$(date +%s); elapsed=$(( now - START ))

  if [ "$elapsed" -ge "$ROTATE_AFTER" ]; then
    log "session age ${elapsed}s hit rotation limit — shutting down cleanly."
    log "re-run serve.sh in a fresh runtime (tunnel URL will change)."
    [ -f "$LOG_DIR/server.pid" ] && kill "$(cat "$LOG_DIR/server.pid")" 2>/dev/null
    [ -f "$LOG_DIR/tunnel.pid" ] && kill "$(cat "$LOG_DIR/tunnel.pid")" 2>/dev/null
    exit 0
  fi

  if ! healthy; then
    log "health check failed — restarting stack"
    [ -f "$LOG_DIR/tunnel.pid" ] && kill "$(cat "$LOG_DIR/tunnel.pid")" 2>/dev/null
    pkill -f "llama_cpp.server" 2>/dev/null
    pkill -f "cloudflared tunnel" 2>/dev/null
    sleep 3
    if [ -x "$(dirname "$0")/serve.sh" ]; then bash "$(dirname "$0")/serve.sh" >>"$LOG_DIR/watchdog.log" 2>&1
    else bash /content/colab-gpu-env/serve.sh >>"$LOG_DIR/watchdog.log" 2>&1; fi
    log "restart issued"
  fi

  sleep "$CHECK_EVERY"
done
