#!/usr/bin/env bash
# Serve Mistral Small 3.1 (UD-Q3_K_XL) on :$PORT with an API key, then open a
# public HTTPS tunnel and print the base URL. Idempotent: kills a previous run.
set -euo pipefail

PORT="${PORT:-8000}"
CTX="${CTX:-8192}"
NGL="${NGL:--1}"                 # -1 = offload every layer to the T4
N_BATCH="${N_BATCH:-512}"
N_THREADS="${N_THREADS:-$(nproc)}"
CHAT_FORMAT="${CHAT_FORMAT:-mistral-instruct}"
ALIAS="${ALIAS:-mistral-small-3.1-24b}"

LOG_DIR="${LOG_DIR:-/content/colab-gpu}"
mkdir -p "$LOG_DIR"

# libllama.so links against libcudart.so.12; setup.sh records where pip put it.
if [ -f /content/colab-gpu/cudalibs.txt ]; then
  export LD_LIBRARY_PATH="$(cat /content/colab-gpu/cudalibs.txt):${LD_LIBRARY_PATH:-}"
fi

MODEL="${MODEL:-$(ls -1 /content/models/*.gguf 2>/dev/null | head -1 || true)}"
if [ -z "${MODEL}" ] || [ ! -f "$MODEL" ]; then
  echo "!! no GGUF found; run colab-gpu-env/setup.sh first" >&2
  exit 1
fi

API_KEY="${COLAB_API_KEY:-$(python3 -c 'import secrets;print(secrets.token_urlsafe(24))')}"
printf '%s' "$API_KEY" > "$LOG_DIR/api_key.txt"
chmod 600 "$LOG_DIR/api_key.txt"

# --- stop any previous instance -------------------------------------------------
for f in server.pid tunnel.pid; do
  if [ -f "$LOG_DIR/$f" ]; then kill "$(cat "$LOG_DIR/$f")" 2>/dev/null || true; rm -f "$LOG_DIR/$f"; fi
done
pkill -f "llama_cpp.server" 2>/dev/null || true
sleep 1

# --- llama.cpp server -----------------------------------------------------------
echo "==> starting llama_cpp.server ($(basename "$MODEL"), ctx=$CTX, ngl=$NGL)"
nohup python3 -m llama_cpp.server \
  --model "$MODEL" \
  --model_alias "$ALIAS" \
  --n_ctx "$CTX" \
  --n_gpu_layers "$NGL" \
  --n_batch "$N_BATCH" \
  --n_threads "$N_THREADS" \
  --chat_format "$CHAT_FORMAT" \
  --api_key "$API_KEY" \
  --host 127.0.0.1 \
  --port "$PORT" \
  > "$LOG_DIR/server.log" 2>&1 &
echo $! > "$LOG_DIR/server.pid"

echo -n "==> waiting for model load"
for _ in $(seq 1 300); do
  if curl -sf -H "Authorization: Bearer $API_KEY" "http://127.0.0.1:$PORT/v1/models" >/dev/null 2>&1; then
    echo " ready"; break
  fi
  if ! kill -0 "$(cat "$LOG_DIR/server.pid")" 2>/dev/null; then
    echo; echo "!! server died; last lines:" >&2; tail -20 "$LOG_DIR/server.log" >&2; exit 1
  fi
  echo -n "."; sleep 2
done

# --- tunnel ---------------------------------------------------------------------
if ! command -v cloudflared >/dev/null; then
  echo "==> installing cloudflared"
  curl -fsSL -o /usr/local/bin/cloudflared \
    https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-amd64
  chmod +x /usr/local/bin/cloudflared
fi

echo "==> opening cloudflared quick tunnel"
nohup cloudflared tunnel --url "http://127.0.0.1:$PORT" --no-autoupdate \
  > "$LOG_DIR/tunnel.log" 2>&1 &
echo $! > "$LOG_DIR/tunnel.pid"

BASE_URL=""
for _ in $(seq 1 60); do
  BASE_URL=$(grep -oE 'https://[a-z0-9-]+\.trycloudflare\.com' "$LOG_DIR/tunnel.log" | head -1 || true)
  [ -n "$BASE_URL" ] && break
  sleep 1
done

if [ -z "$BASE_URL" ]; then
  echo "!! tunnel URL not found; see $LOG_DIR/tunnel.log" >&2; exit 1
fi

cat <<EOF

────────────────────────────────────────────────────────────
 base_url : $BASE_URL/v1
 api_key  : $API_KEY
 model    : $ALIAS
 ctx      : $CTX (ngl=$NGL, batch=$N_BATCH)
 logs     : $LOG_DIR/{server,tunnel}.log

 curl smoke test:
   curl -s "$BASE_URL/v1/chat/completions" \\
     -H "Authorization: Bearer $API_KEY" -H 'Content-Type: application/json' \\
     -d '{"model":"$ALIAS","messages":[{"role":"user","content":"hi"}],"max_tokens":32}'
────────────────────────────────────────────────────────────
EOF

# Let clients discover the new URL + key without being rebuilt.
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
if [ -x "$SCRIPT_DIR/scripts/publish_endpoint.sh" ] && [ -n "${GITHUB_TOKEN:-}" ]; then
  "$SCRIPT_DIR/scripts/publish_endpoint.sh" || echo "publish: failed (non-fatal)" >&2
fi
