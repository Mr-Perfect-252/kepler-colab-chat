#!/usr/bin/env bash
# Publish the live endpoint so a client can recover from a restart or key rotation.
#
# serve.sh mints a NEW api key and a NEW tunnel host every boot, so anything
# hardcoded in an APK goes stale. This writes the current triple here:
#
#   { "base_url": "https://x.trycloudflare.com/v1", "api_key": "...",
#     "model": "...", "updated_at": 1793899087, "session": "deploy2" }
#
# so the app can re-read it after a failed ping and reconnect unattended.
#
# Modes (MODE env var):
#   repo  (default) commits endpoint.json to a branch via the Contents API
#   gist            PATCHes an existing gist (needs `gist` scope on the token)
#
#   GITHUB_TOKEN=ghp_... PUBLISH_REPO=owner/repo MODE=repo ./publish_endpoint.sh
set -uo pipefail

API_KEY_FILE="${API_KEY_FILE:-/content/colab-gpu/api_key.txt}"
MODEL="${MODEL:-mistral-small-3.1-24b}"
MODE="${MODE:-repo}"
TOKEN="${GITHUB_TOKEN:-}"
REPO="${PUBLISH_REPO:-Mr-Perfect-252/Kepler-Endpoint}"
BRANCH="${PUBLISH_BRANCH:-main}"
GIST_ID="${GIST_ID:-}"

[ -z "$TOKEN" ] && { echo "publish: GITHUB_TOKEN unset, skipping" >&2; exit 0; }
[ -f "$API_KEY_FILE" ] || { echo "publish: no api key at $API_KEY_FILE" >&2; exit 1; }

BASE_URL=$(grep -oE 'https://[a-z0-9-]+\.trycloudflare\.com/v1' /content/serve.log 2>/dev/null | tail -1)
[ -z "$BASE_URL" ] && { echo "publish: no tunnel URL in serve.log" >&2; exit 1; }

BODY=$(python3 - "$BASE_URL" "$(cat "$API_KEY_FILE")" "$MODEL" <<'PY'
import json, sys, time, os
print(json.dumps({
    "base_url": sys.argv[1],
    "api_key": sys.argv[2],
    "model": sys.argv[3],
    "updated_at": int(time.time()),
    "session": os.environ.get("COLAB_SESSION", "unknown"),
}))
PY
)

echo "publish: $BODY"

if [ "$MODE" = "gist" ]; then
  [ -z "$GIST_ID" ] && { echo "publish: GIST_ID unset" >&2; exit 1; }
  curl -sS -X PATCH "https://api.github.com/gists/$GIST_ID" \
    -H "Authorization: token $TOKEN" -H "Accept: application/vnd.github+json" \
    -d "$(python3 -c 'import json,sys; print(json.dumps({"files":{"endpoint.json":{"content":sys.argv[1]}}}))' "$BODY")" \
    -o /tmp/pub.json -w "publish: gist HTTP %{http_code}\n"
else
  PATH_IN_REPO="${PUBLISH_PATH:-endpoint.json}"
  API="https://api.github.com/repos/$REPO/contents/$PATH_IN_REPO"
  SHA=$(curl -sS -H "Authorization: token $TOKEN" "$API?ref=$BRANCH" \
        | python3 -c 'import json,sys; d=json.load(sys.stdin); print(d.get("sha",""))' 2>/dev/null)
  python3 - "$BODY" > /tmp/pub_body.json <<'PY'
import base64, json, sys
print(json.dumps({
    "message": "chore: publish colab endpoint",
    "content": base64.b64encode(sys.argv[1].encode()).decode(),
    "branch": "main",
}))
PY
  [ -n "$SHA" ] && python3 - "$SHA" <<'PY'
import json, sys
p = "/tmp/pub_body.json"
d = json.load(open(p)); d["sha"] = sys.argv[1]; json.dump(d, open(p, "w"))
PY
  curl -sS -X PUT "$API" -H "Authorization: token $TOKEN" \
    -H "Accept: application/vnd.github+json" -d @/tmp/pub_body.json \
    -o /tmp/pub.json -w "publish: repo HTTP %{http_code}\n"
fi

head -c 300 /tmp/pub.json 2>/dev/null; echo
