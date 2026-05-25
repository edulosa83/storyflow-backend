#!/usr/bin/env bash
set -euo pipefail

# Creates/refreshes a local Instaloader session file using interactive login.
# Password is requested by Instaloader in terminal (not stored in this script).
# Optional:
#   USE_COOKIES_BROWSER=brave|chrome|firefox|safari ...
#   COOKIE_FILE=/path/to/Cookies (only if browser profile is non-default)

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
USE_COOKIES_BROWSER="${USE_COOKIES_BROWSER:-}"
COOKIE_FILE="${COOKIE_FILE:-}"

mkdir -p "$(dirname "$SESSION_FILE")"

tmp_session="${SESSION_FILE}.new"
rm -f "$tmp_session"

common_args=(
  --sessionfile "$tmp_session"
  --max-connection-attempts 1
  --no-posts
  --no-profile-pic
  --no-videos
  --no-video-thumbnails
  --no-metadata-json
  "$IG_USERNAME"
)

if [[ -n "$USE_COOKIES_BROWSER" ]]; then
  cookie_args=(--load-cookies "$USE_COOKIES_BROWSER")
  if [[ -n "$COOKIE_FILE" ]]; then
    cookie_args+=(--cookiefile "$COOKIE_FILE")
  fi
  instaloader "${cookie_args[@]}" "${common_args[@]}"
else
  instaloader --login "$IG_USERNAME" "${common_args[@]}"
fi

mv "$tmp_session" "$SESSION_FILE"

echo "Sesión renovada en: $SESSION_FILE"
