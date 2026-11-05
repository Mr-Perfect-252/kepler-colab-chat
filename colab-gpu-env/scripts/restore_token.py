#!/usr/bin/env python3
"""Restore Colab credentials from a saved token blob — e.g. an env var.

    export COLAB_TOKEN_JSON='{"token": "...", "refresh_token": "...", ...}'
    python3 colab-gpu-env/scripts/restore_token.py

Then: colab whoami && colab usage

Only `refresh_token` really matters (the access token expires hourly). Storing the
blob yourself — rather than committing it — keeps a long-lived Google credential out
of git history, where it would survive every future rotation of the repo's secrets.
"""
import json
import os
import sys

TOKEN_PATH = os.path.expanduser("~/.config/colab-cli/token.json")
GRANTED = [
    "openid",
    "https://www.googleapis.com/auth/userinfo.profile",
    "https://www.googleapis.com/auth/userinfo.email",
    "https://www.googleapis.com/auth/colaboratory",
]


def main():
    blob = os.environ.get("COLAB_TOKEN_JSON", "").strip()
    if not blob and len(sys.argv) > 1:
        blob = open(sys.argv[1]).read().strip()
    if not blob:
        sys.exit("set COLAB_TOKEN_JSON (or pass a path to a token json)")

    data = json.loads(blob)
    if not data.get("refresh_token"):
        sys.exit("blob has no refresh_token — it cannot be refreshed")

    # Google withholds cloud-platform/drive.file for this client; claiming them
    # makes every refresh fail invalid_scope.
    data["scopes"] = GRANTED

    os.makedirs(os.path.dirname(TOKEN_PATH), exist_ok=True)
    with open(TOKEN_PATH, "w") as f:
        json.dump(data, f)
    os.chmod(TOKEN_PATH, 0o600)
    print(f"wrote {TOKEN_PATH} (mode 600)")
    print("next: colab whoami && colab usage")


if __name__ == "__main__":
    main()
