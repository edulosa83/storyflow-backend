#!/usr/bin/env bash
set -euo pipefail

# Creates/refreshes a local Instaloader session file using interactive login.
# Password is requested by Instaloader in terminal (not stored in this script).

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

: "${IG_USERNAME:?Define IG_USERNAME en backend/.env o variable de entorno}"

SESSION_FILE="${SESSION_FILE:-${IG_SESSION_FILE:-$HOME/.config/instaloader/session-storyflow-bootstrap}}"
mkdir -p "$(dirname "$SESSION_FILE")"

instaloader \
  --login "$IG_USERNAME" \
  --sessionfile "$SESSION_FILE" \
  --no-posts \
  --no-profile-pic \
  --no-videos \
  --no-video-thumbnails \
  --no-metadata-json \
  "$IG_USERNAME"

echo "Sesión renovada en: $SESSION_FILE"
