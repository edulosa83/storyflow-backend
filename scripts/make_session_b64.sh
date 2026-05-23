#!/usr/bin/env bash
set -euo pipefail

SESSION_FILE="${1:-$HOME/.config/instaloader/session-storyflow-bootstrap}"

if [[ ! -f "$SESSION_FILE" ]]; then
  echo "No existe el archivo de sesión: $SESSION_FILE" >&2
  exit 1
fi

base64 < "$SESSION_FILE" | tr -d '\n'
echo
