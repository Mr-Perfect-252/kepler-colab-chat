#!/usr/bin/env python3
"""Print one JSON line describing the VM's state. Used by CI to poll progress.

    colab exec -s revive -f colab-gpu-env/scripts/status.py
"""
import json
import os
import re
import subprocess

SETUP_LOG = "/content/setup.log"
SERVE_LOG = "/content/serve.log"
KEY_FILE = "/content/colab-gpu/api_key.txt"


def tail(path: str) -> str:
    try:
        return open(path, errors="replace").read()
    except OSError:
        return ""


setup, serve = tail(SETUP_LOG), tail(SERVE_LOG)
urls = re.findall(r"https://[a-z0-9-]+\.trycloudflare\.com/v1", serve)

key = ""
if os.path.exists(KEY_FILE):
    key = open(KEY_FILE).read().strip()

try:
    gpu = subprocess.run(
        ["nvidia-smi", "--query-gpu=memory.used,memory.total", "--format=csv,noheader"],
        capture_output=True, text=True, timeout=10).stdout.strip()
except Exception:
    gpu = "n/a"

print(json.dumps({
    "setup_done": "next: bash colab-gpu-env/serve.sh" in setup,
    "setup_error": "!!" in setup.splitlines()[-1] if setup else False,
    "url": urls[-1] if urls else "",
    "api_key": key,
    "gpu": gpu,
    "serve_log_tail": serve.splitlines()[-3:],
}))
