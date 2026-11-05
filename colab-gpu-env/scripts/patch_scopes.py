#!/usr/bin/env python3
"""Rewrite colab_cli's scope list to what Google actually grants.

Google withholds `cloud-platform` and `drive.file` for this client, but the CLI
passes its own six-scope list into `from_authorized_user_file()`, which overrides
the token file. Every refresh then asks for a superset and fails `invalid_scope`.

Run after `pip install google-colab-cli`, before any other colab command.
"""
import importlib.util
import json
import os
import sys

GRANTED = [
    "openid",
    "https://www.googleapis.com/auth/userinfo.profile",
    "https://www.googleapis.com/auth/userinfo.email",
    "https://www.googleapis.com/auth/colaboratory",
]


def cli_root() -> str:
    spec = importlib.util.find_spec("colab_cli")
    if spec is None:
        sys.exit("google-colab-cli not installed")
    roots = list(spec.submodule_search_locations or [])
    if spec.origin and spec.origin != "namespace":
        roots.append(os.path.dirname(spec.origin))
    return roots[0]


def main() -> None:
    root = cli_root()

    auth_py = os.path.join(root, "auth.py")
    src = open(auth_py).read()
    start = src.index("PUBLIC_SCOPES = [")
    end = src.index("]", start) + 1
    new = "PUBLIC_SCOPES = [\n" + "".join(f'    "{s}",\n' for s in GRANTED) + "]"
    open(auth_py, "w").write(src[:start] + new + src[end:])
    print(f"patched {auth_py}")

    token_path = os.path.expanduser("~/.config/colab-cli/token.json")
    if os.path.exists(token_path):
        data = json.load(open(token_path))
        data["scopes"] = GRANTED
        with open(token_path, "w") as f:
            json.dump(data, f)
        os.chmod(token_path, 0o600)
        print(f"rewrote scopes in {token_path}")

    print("scopes:", ", ".join(s.rsplit("/", 1)[-1] for s in GRANTED))


if __name__ == "__main__":
    main()
