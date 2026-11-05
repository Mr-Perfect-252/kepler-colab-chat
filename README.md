# kepler-colab-chat

A native Android chat client for **Mistral Small 3.1 (24B)** running on a free-tier
**Colab T4**, with unattended revival when the runtime dies.

## The three pieces

| Piece | Role |
|---|---|
| `app/` | Kotlin + Compose client. Pings the endpoint, streams tokens, reconnects. |
| `.github/workflows/build-apk.yml` | Builds the debug APK with secrets injected at compile time. |
| `.github/workflows/revive-colab.yml` | Boots a Colab T4, serves the model, publishes `endpoint.json`. |

## Why revival lives in Actions

A phone cannot mint Colab credentials or start a GPU VM. A workflow runner can do
both: it holds the refresh token, runs `colab new --gpu T4`, starts the server and
commits the new endpoint. The app's only contribution is a `repository_dispatch`.

```
ping fails ──► re-read endpoint.json ──► still fails
                                            │
                                            ▼
                              POST /repos/…/dispatches {event_type: revive}
                                            │
                        Actions boots T4 → serves → commits endpoint.json
                                            │
                              app polls → reconnects ~3-5 min later
```

## Required repo secrets

| Secret | What |
|---|---|
| `COLAB_TOKEN_JSON` | Output of the OAuth exchange (refresh token + client id/secret). |
| `GH_PAT` | Baked into the APK so it can fire `repository_dispatch`. |
| `BOOTSTRAP_URL` / `BOOTSTRAP_KEY` | Last known endpoint, for first launch. |
| `RENDEZVOUS_URL` | Raw URL of `endpoint.json` on `main`. |

`endpoint.json` is written by the revive workflow and is gitignored locally.

## Build

Push to `main` → **Actions → build-apk** → download the `colab-chat-debug` artifact.
