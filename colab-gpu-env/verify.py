#!/usr/bin/env python3
"""Self-test against a running colab-gpu-env endpoint.

Reads BASE_URL / COLAB_API_KEY from the environment, falling back to
/content/colab-gpu/api_key.txt. Prints measured tok/s so the numbers in the
README can be replaced with reality rather than estimates.

    python3 colab-gpu-env/verify.py
    BASE_URL=https://x.trycloudflare.com/v1 COLAB_API_KEY=... python3 verify.py
"""
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.request

API_KEY_FILE = "/content/colab-gpu/api_key.txt"


def api_key() -> str:
    key = os.environ.get("COLAB_API_KEY", "").strip()
    if key:
        return key
    if os.path.exists(API_KEY_FILE):
        return open(API_KEY_FILE).read().strip()
    sys.exit("no API key: set COLAB_API_KEY or run serve.sh first")


def base_url() -> str:
    url = os.environ.get("BASE_URL", "").strip()
    if url:
        return url.rstrip("/")
    sys.exit("set BASE_URL to the tunnel, e.g. https://xxx.trycloudflare.com/v1")


def call(url, key, payload=None, timeout=300):
    data = json.dumps(payload).encode() if payload else None
    req = urllib.request.Request(
        url, data=data,
        headers={"Authorization": f"Bearer {key}", "Content-Type": "application/json"},
        method="POST" if data else "GET")
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return json.loads(r.read())
    except urllib.error.HTTPError as e:
        sys.exit(f"HTTP {e.code} from {url}: {e.read()[:300].decode(errors='replace')}")


def gpu() -> str:
    try:
        return subprocess.run(
            ["nvidia-smi", "--query-gpu=name,memory.used,memory.total",
             "--format=csv,noheader"],
            capture_output=True, text=True).stdout.strip()
    except FileNotFoundError:
        return "no nvidia-smi (not a GPU runtime?)"


def main():
    url, key = base_url(), api_key()
    print(f"endpoint : {url}")
    print(f"gpu      : {gpu()}")

    models = call(f"{url}/models", key)
    served = [m["id"] for m in models.get("data", [])]
    print(f"models   : {served}")
    if not served:
        sys.exit("server exposes no models")
    model = served[0]

    prompt = ("Explain in exactly three sentences why a 24B model at 3-bit "
              "quantisation can fit on a 15GB GPU while a 7B model at FP16 cannot.")
    t0 = time.time()
    out = call(f"{url}/chat/completions", key, {
        "model": model,
        "messages": [{"role": "user", "content": prompt}],
        "max_tokens": 180,
        "temperature": 0.2,
    })
    wall = time.time() - t0

    msg = out["choices"][0]["message"]["content"].strip()
    usage = out.get("usage", {})
    timings = out.get("timings", {})
    completion_toks = usage.get("completion_tokens") or timings.get("predicted_n")

    print("\n--- completion ---------------------------------------------")
    print(msg[:600] + ("…" if len(msg) > 600 else ""))
    print("------------------------------------------------------------")
    print(f"completion tokens : {completion_toks}")
    print(f"wall clock        : {wall:.2f}s")

    if timings.get("predicted_ms") and timings.get("predicted_n"):
        tps = timings["predicted_n"] / (timings["predicted_ms"] / 1000)
        print(f"decode throughput : {tps:.2f} tok/s (server-reported)")
    elif completion_toks:
        print(f"decode throughput : {completion_toks / wall:.2f} tok/s (incl. prefill)")

    if not msg:
        sys.exit("\nFAIL: empty completion")
    print("\nPASS")


if __name__ == "__main__":
    main()
