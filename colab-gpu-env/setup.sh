#!/usr/bin/env bash
# Install the CUDA-enabled llama.cpp bindings (prebuilt wheel, no compile) and
# pull the GGUF that actually fits a 15.36 GB T4 with usable context.
set -euo pipefail

MODEL_DIR="${MODEL_DIR:-/content/models}"
REPO="${REPO:-unsloth/Mistral-Small-3.1-24B-Instruct-2503-GGUF}"
QUANT="${QUANT:-UD-Q3_K_XL}"
FILE="Mistral-Small-3.1-24B-Instruct-2503-${QUANT}.gguf"

echo "==> GPU check"
if ! command -v nvidia-smi >/dev/null; then
  echo "!! nvidia-smi missing: this runtime has no GPU (colab new --gpu T4)" >&2
  exit 1
fi
nvidia-smi --query-gpu=name,memory.total --format=csv,noheader

echo "==> Installing llama-cpp-python (CUDA wheel, py3-none-manylinux_2_35)"
# The cu124 wheels are ABI-agnostic (py3-none), so they work on 3.11/3.12/3.13
# without a 20-minute nvcc build on 2 vCPU.
python3 -m pip install -q --only-binary=:all: "llama-cpp-python==0.3.36" \
  --extra-index-url https://abetlen.github.io/llama-cpp-python/whl/cu124
python3 -m pip install -q "huggingface_hub[hf_transfer]"

# llama_cpp.server imports its web stack at startup; the wheel does not pull it in.
python3 -m pip install -q sse-starlette uvicorn fastapi pydantic-settings starlette starlette-context pyyaml

echo "==> CUDA 12 runtime (Colab ships CUDA 13; the cu124 wheel needs libcudart.so.12)"
# Without this: RuntimeError: Failed to load .../libllama.so: libcudart.so.12:
# cannot open shared object file. The wheel links against the CUDA 12 runtime,
# and the driver is only backward compatible at the *driver* level — the cudart
# userland .so still has to exist somewhere on the loader path.
python3 -m pip install -q nvidia-cuda-runtime-cu12 nvidia-cublas-cu12
mkdir -p /content/colab-gpu
python3 - <<'PY' > /content/colab-gpu/cudalibs.txt
import os
import site
paths = []
for sp in site.getsitepackages() + [site.getusersitepackages()]:
    for sub in ("nvidia/cuda_runtime/lib", "nvidia/cublas/lib"):
        d = os.path.join(sp, sub)
        if os.path.isdir(d):
            paths.append(d)
# The *driver* library (libcuda.so.1) is not in a pip package. On Colab it lives
# in /usr/lib64-nvidia; without it libllama.so fails with `libcuda.so.1: cannot
# open shared object file` even after the cudart/cublas fix above.
for d in ("/usr/lib64-nvidia", "/usr/local/cuda/compat"):
    if os.path.isdir(d):
        paths.append(d)
print(":".join(paths))
PY
export LD_LIBRARY_PATH="$(cat /content/colab-gpu/cudalibs.txt):${LD_LIBRARY_PATH:-}"
echo "    LD_LIBRARY_PATH=$LD_LIBRARY_PATH"

echo "==> Downloading ${QUANT} from ${REPO}"
mkdir -p "$MODEL_DIR"
HF_HUB_ENABLE_HF_TRANSFER=1 python3 - "$REPO" "$FILE" "$MODEL_DIR" <<'PY'
import sys
from huggingface_hub import hf_hub_download
repo, filename, local_dir = sys.argv[1], sys.argv[2], sys.argv[3]
p = hf_hub_download(repo_id=repo, filename=filename, local_dir=local_dir)
import os
print("MODEL:", p, f"{os.path.getsize(p)/1e9:.2f} GB")
PY

echo "==> CUDA backend visibility"
python3 -c "
import llama_cpp, subprocess
print('llama_cpp', llama_cpp.__version__)
print(subprocess.run(['nvidia-smi','--query-gpu=memory.used,memory.total','--format=csv,noheader'],capture_output=True,text=True).stdout.strip())
"

echo "==> done. next: bash colab-gpu-env/serve.sh"
