#!/usr/bin/env python3
"""Call the deployed endpoint with the OpenAI SDK — batch research jobs included.

    export BASE_URL=https://xxx.trycloudflare.com/v1
    export COLAB_API_KEY=...
    python3 scripts/client_example.py
"""
import os
import sys

from openai import OpenAI

client = OpenAI(
    base_url=os.environ.get("BASE_URL", "http://127.0.0.1:8000/v1"),
    api_key=os.environ.get("COLAB_API_KEY", "sk-local"),
    timeout=600,
)

MODEL = os.environ.get("MODEL_ALIAS", "mistral-small-3.1-24b")


def once(prompt: str, **kw) -> str:
    r = client.chat.completions.create(
        model=MODEL,
        messages=[{"role": "user", "content": prompt}],
        temperature=kw.pop("temperature", 0.2),
        max_tokens=kw.pop("max_tokens", 512),
        **kw,
    )
    return r.choices[0].message.content


def batch(prompts, system="You are a careful research assistant."):
    """Sequential by design: a single T4 has one compute stream, so concurrent
    requests just split the same tok/s and inflate latency."""
    out = []
    for i, p in enumerate(prompts, 1):
        r = client.chat.completions.create(
            model=MODEL,
            messages=[{"role": "system", "content": system},
                      {"role": "user", "content": p}],
            temperature=0.1,
            max_tokens=400,
        )
        txt = r.choices[0].message.content
        print(f"[{i}/{len(prompts)}] {len(txt)} chars")
        out.append(txt)
    return out


if __name__ == "__main__":
    if not os.environ.get("BASE_URL"):
        print("hint: set BASE_URL to the tunnel printed by serve.sh\n", file=sys.stderr)
    print(once("State the difference between quantisation and distillation in one line."))
    print("\n--- batch ---")
    for r in batch(["Summarise the KV-cache memory formula for GQA.",
                    "Give two failure modes of 3-bit quantisation in long-context evals."]):
        print("\n" + r[:400])
