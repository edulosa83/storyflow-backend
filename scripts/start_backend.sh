#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR/backend"

if [[ ! -d .venv ]]; then
  python3 -m venv .venv
fi

source .venv/bin/activate

if [[ -f .env ]]; then
  set -a
  source .env
  set +a
fi

: "${IG_USERNAME:?Define IG_USERNAME (en backend/.env o variable de entorno)}"

if [[ -z "${IG_SESSION_FILE:-}" && -z "${IG_SESSION_B64:-}" ]]; then
  echo "Define IG_SESSION_FILE o IG_SESSION_B64 (en backend/.env o variable de entorno)." >&2
  exit 1
fi

exec uvicorn app:app --host 0.0.0.0 --port 8080
