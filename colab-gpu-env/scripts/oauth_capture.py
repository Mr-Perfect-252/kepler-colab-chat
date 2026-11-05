#!/usr/bin/env python3
"""Headless OAuth for google-colab-cli (there is no Colab API key).

Works on a box with no browser and no localhost callback: the CLI's own flow is a
copy-paste OAuth code, same as `gcloud auth application-default login`.

    python3 scripts/oauth_capture.py start          # prints a URL
    # open it, approve, copy the 4/0A... code
    python3 scripts/oauth_capture.py finish 4/0A...

Two non-obvious details this handles:

1. STATIC PKCE verifier. The normal flow derives a random verifier and keeps it in
   process memory, so a sandbox reset between "print URL" and "paste code" makes the
   code unredeemable. A fixed verifier makes the exchange reproducible from any
   instance. (The client secret below is Google's public Cloud SDK client, so PKCE
   strictness is not the load-bearing control here.)

2. SCOPE PATCH. Google grants a subset of the requested scopes (it silently drops
   `cloud-platform` and `drive.file` for this client), but colab_cli passes its own
   6-scope list to `from_authorized_user_file()`, which *overrides* the token file's
   scopes. The refresh then requests a superset of what was granted and every call
   fails `invalid_scope`. We write the granted set into the token AND into the CLI.
"""
import base64
import hashlib
import json
import os
import subprocess
import sys

os.environ["OAUTHLIB_RELAX_TOKEN_SCOPE"] = "1"

from google_auth_oauthlib.flow import InstalledAppFlow  # noqa: E402

# Fixed verifier -> reproducible challenge (see note 1 above).
VERIFIER = "colab-cli-alt-verifier-7Q2mZ9xLpR4vT8nK3jH6yB1wS5dF0gA2cE9uM"
CHALLENGE = base64.urlsafe_b64encode(
    hashlib.sha256(VERIFIER.encode()).digest()).decode().rstrip("=")

REDIRECT = "https://sdk.cloud.google.com/applicationdefaultauthcode.html"
TOKEN_PATH = os.path.expanduser("~/.config/colab-cli/token.json")

# What Google actually grants for this client, and what the Colab session backend
# needs (`userinfo.email` -> 401 without it; `colaboratory` for Colab APIs).
GRANTED = [
    "openid",
    "https://www.googleapis.com/auth/userinfo.profile",
    "https://www.googleapis.com/auth/userinfo.email",
    "https://www.googleapis.com/auth/colaboratory",
]
REQUEST = GRANTED + [
    "https://www.googleapis.com/auth/cloud-platform",
    "https://www.googleapis.com/auth/drive.file",
]


def _cli_file(name: str) -> str:
    try:
        import colab_cli  # noqa: F401
    except ImportError:
        sys.exit("google-colab-cli not installed: pip install google-colab-cli")
    out = subprocess.run(
        [sys.executable, "-c",
         f"import colab_cli,os;print(os.path.join(os.path.dirname(colab_cli.__file__),'{name}'))"],
        capture_output=True, text=True, check=True).stdout.strip()
    return out


def build():
    flow = InstalledAppFlow.from_client_secrets_file(_cli_file("oauth_config.json"), REQUEST)
    flow.redirect_uri = REDIRECT
    flow.code_verifier = VERIFIER
    return flow


def start():
    flow = build()
    url, _ = flow.authorization_url(prompt="consent", token_usage="remote")
    print("\nOpen this in any browser (use the account whose Colab you want):\n")
    print(url + "\n")
    print(f"code_challenge={CHALLENGE}  (fixed — safe to run `finish` from another shell)")


def patch_cli_scopes():
    p = _cli_file("auth.py")
    src = open(p).read()
    a = src.index("PUBLIC_SCOPES = [")
    b = src.index("]", a) + 1
    new = "PUBLIC_SCOPES = [\n" + "".join(f'    "{s}",\n' for s in GRANTED) + "]"
    open(p, "w").write(src[:a] + new + src[b:])


def write_token(data):
    os.makedirs(os.path.dirname(TOKEN_PATH), exist_ok=True)
    data["scopes"] = GRANTED
    with open(TOKEN_PATH, "w") as f:
        json.dump(data, f, indent=2)
    os.chmod(TOKEN_PATH, 0o600)


def finish(code):
    flow = build()
    try:
        flow.fetch_token(code=code.strip())
    except Exception as e:  # noqa: BLE001  (creds may still be populated)
        print("fetch_token raised:", type(e).__name__, str(e)[:160])
    creds = flow.credentials
    if creds is None or not creds.refresh_token:
        sys.exit("no refresh token — the code is single-use and may have expired (10 min)")
    data = json.loads(creds.to_json())
    write_token(data)
    patch_cli_scopes()
    print(f"saved {TOKEN_PATH} (mode 600)")
    print("refresh token present:", bool(data.get("refresh_token")))
    print("\nverify: colab whoami && colab usage")


if __name__ == "__main__":
    if len(sys.argv) < 2 or sys.argv[1] not in ("start", "finish"):
        sys.exit(__doc__)
    start() if sys.argv[1] == "start" else finish(sys.argv[2])
