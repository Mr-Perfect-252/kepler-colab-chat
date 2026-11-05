# `colab-gpu-env/` — Mistral Small on a Colab T4, served as an OpenAI-compatible API

**Problem it solves.** Getting a 24B research model running on a **free-tier Colab
T4 (15.36 GB VRAM)** and reachable from outside the notebook — headlessly, with no
browser tab, no Colab UI, and no manual clicking. The obstacles, in order:

1. Auth is OAuth-only. There is no API key. `colab-mcp` needs a live browser session
   and a localhost WebSocket, so it cannot drive a headless box.
2. Mistral Small 3.1 is **24B params** — FP16 is 47 GB, Q4_K_M is 14.33 GB, and a
   T4 has 15.36 GB *total*, leaving nothing for KV cache. Q4 therefore OOMs at any
   usable context.
3. `llama-cpp-python` normally compiles against CUDA for ~20 min on 2 vCPU.
4. Colab kills sessions (~12 h) and preempts them without warning.

**The fix.** `google-colab-cli` (headless OAuth + a real VM, no browser), plus
`llama_cpp.server` on the dynamically-quantised `UD-Q3_K_XL` build (**11.85 GB**,
leaving ~3.5 GB for 8k context) using the prebuilt `py3-none-manylinux_2_35`
CUDA wheel — no compile. A `cloudflared` quick tunnel exposes `/v1`.

---

## Contents

| Path | What |
|------|------|
| `setup.sh` | Installs the CUDA wheel + pulls the GGUF, verifies the GPU is visible. ~2 min. |
| `serve.sh` | Starts `llama_cpp.server` on `:8000` with an API key, then opens a tunnel and prints the URL. |
| `watchdog.sh` | Restarts the server if it dies; exits at the 12 h session cap for rotation. |
| `verify.py` | Self-test: loads the model, measures real tok/s, asserts a completion round-trips. |
| `requirements.txt` | Pinned versions verified on T4 / py3.13 / CUDA 13 driver. |
| `scripts/oauth_capture.py` | Headless OAuth with a **static PKCE verifier** (survives sandbox resets). |
| `scripts/client_example.py` | OpenAI SDK client pointed at the tunnel. |

> The GGUF (~11.85 GB) is **not** in git. `setup.sh` downloads it to `/content/models`.

---

## Why `UD-Q3_K_XL` and not Q4

| Quant | Size | Fits T4 w/ 8k ctx? | Notes |
|---|---|---|---|
| `Q4_K_M` | 14.33 GB | ✗ | ~1 GB left → OOM under load |
| `UD-Q4_K_XL` | 14.51 GB | ✗ | worse |
| **`UD-Q3_K_XL`** | **11.85 GB** | **✓** | Unsloth dynamic quant; ~3.5 GB headroom |
| `Q3_K_M` | 11.47 GB | ✓ | slightly worse than UD at same size |

KV cache for this model at fp16 is ≈ 131 KB/token, so 8192 ctx ≈ 1.05 GB.
That is the budget `serve.sh` assumes. Pushing `CTX` past ~12288 will OOM.

> If you have Colab Pro (compute units), skip all of this and run the Q5/Q6 build
> or vLLM on an L4/A100 — the T4 is the free-tier ceiling, not a design choice.

## Measured on a live free-tier T4

```
gpu      : Tesla T4, 15360 MiB (driver 580.82.07, nvcc 13.0)
resident : 12.9 GB VRAM after load, ctx 8192, ngl=-1
decode   : 9.29 tok/s  (101 tokens in 10.9 s, includes prefill)
model    : Mistral-Small-3.1-24B-Instruct-2503-UD-Q3_K_XL.gguf, 11.85 GB
```

Good for batch research and eval sweeps; noticeably slow for interactive chat.

## Three loader traps (all hit in practice)

`llama_cpp` imports fine on a plain `pip install` and then fails at load time. In order:

1. `libcudart.so.12: cannot open shared object file` — Colab ships CUDA **13**; the
   cu124 wheel still needs the 12 runtime. Fix: `pip install nvidia-cuda-runtime-cu12
   nvidia-cublas-cu12` and put those dirs on `LD_LIBRARY_PATH`.
2. `libcuda.so.1: cannot open shared object file` — the *driver* lib is not in any pip
   package. On Colab it lives in **`/usr/lib64-nvidia`**. Add it to the path.
3. `ModuleNotFoundError: sse_starlette` / `starlette_context` — `llama_cpp.server`
   imports its web stack at startup but the wheel doesn't depend on it. Install
   `sse-starlette uvicorn fastapi pydantic-settings starlette starlette-context pyyaml`.

`setup.sh` handles all three and writes the resolved paths to
`/content/colab-gpu/cudalibs.txt`, which `serve.sh` re-exports.

---

## Quick start

```bash
# 0. Auth once (writes ~/.config/colab-cli/token.json; refresh token persists)
python3 colab-gpu-env/scripts/oauth_capture.py start     # open URL, paste code
python3 colab-gpu-env/scripts/oauth_capture.py finish <CODE>

colab new --gpu T4                    # provision (CPU also works, no GPU accel)
bash  colab-gpu-env/setup.sh          # install + download ~12 GB
bash  colab-gpu-env/serve.sh          # serve + tunnel, prints base_url + key
python3 colab-gpu-env/verify.py       # tok/s self-test
```

Smoke test:

```bash
curl -s "$BASE_URL/v1/chat/completions" \
  -H "Authorization: Bearer $COLAB_API_KEY" \
  -H 'Content-Type: application/json' \
  -d '{"model":"mistral-small-3.1-24b","messages":[{"role":"user","content":"hi"}],"max_tokens":32}'
```

---

## Operational reality (read before planning a 15 h/week budget)

* **Session cap.** Free-tier T4 sessions die at ~12 h and can be preempted any time.
  `watchdog.sh` rotates at 11 h 30 m so a restart is deliberate rather than mid-job.
  Anything long-running must checkpoint to Drive or download output before that.
* **The tunnel URL changes on every restart** (quick tunnels are anonymous). Pinning a
  hostname needs a named Cloudflare tunnel + a domain, out of scope here.
* **Compute units.** `colab usage` reports balance. Free tier shows `0.00` and still
  provisions a T4 — accelerators draw from a shared pool, so capacity is best-effort.
* **Cost of the quant.** 3-bit on a 24B model is noticeably weaker than Q5+ on long
  reasoning chains. For evals where quality dominates, run the Q5_K_M build on a
  rented A100 instead; this env optimises for *fit and availability*, not peak quality.

## Security

* Nothing in this repo contains a Google token or a GitHub token. `oauth_capture.py`
  writes the refresh token to `~/.config/colab-cli/token.json` (mode 600) only.
* The tunnel is **public** — anyone with the URL and key can spend your GPU time.
  `serve.sh` generates a random key per boot; share it like a password.
* The OAuth client secret in `google-colab-cli` is the public Cloud SDK client. It is
  not a secret, and its presence is why no OAuth app registration is needed.
