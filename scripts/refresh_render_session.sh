#!/usr/bin/env bash
set -euo pipefail

# Refresh IG_SESSION_B64 in Render and trigger a redeploy.
# Usage:
#   RENDER_API_KEY=... ./scripts/refresh_render_session.sh
#   RENDER_API_KEY=... SESSION_FILE=/path/to/session ./scripts/refresh_render_session.sh

RENDER_API_KEY="${RENDER_API_KEY:-}"
SERVICE_NAME="${SERVICE_NAME:-storyflow-backend}"
SERVICE_ID="${SERVICE_ID:-}"
SESSION_FILE="${SESSION_FILE:-$HOME/.config/instaloader/session-storyflow-bootstrap}"

if [[ -z "$RENDER_API_KEY" ]]; then
  echo "Falta RENDER_API_KEY en variables de entorno." >&2
  exit 1
fi

if [[ ! -f "$SESSION_FILE" ]]; then
  echo "No existe SESSION_FILE: $SESSION_FILE" >&2
  exit 1
fi

if [[ -z "$SERVICE_ID" ]]; then
  services_json="$(curl -sS \
    -H "Authorization: Bearer $RENDER_API_KEY" \
    -H 'Accept: application/json' \
    'https://api.render.com/v1/services?limit=100')"
  SERVICE_ID="$(echo "$services_json" | jq -r ".[]?.service? | select(.name==\"$SERVICE_NAME\") | .id" | head -n1)"
  if [[ -z "$SERVICE_ID" ]]; then
    SERVICE_ID="$(echo "$services_json" | jq -r ".[]? | select(.name==\"$SERVICE_NAME\") | .id" | head -n1)"
  fi
fi

if [[ -z "$SERVICE_ID" ]]; then
  echo "No se encontró SERVICE_ID para SERVICE_NAME=$SERVICE_NAME" >&2
  exit 1
fi

session_b64="$(base64 < "$SESSION_FILE" | tr -d '\n')"

curl -sS -X PUT \
  -H "Authorization: Bearer $RENDER_API_KEY" \
  -H 'Accept: application/json' \
  -H 'Content-Type: application/json' \
  "https://api.render.com/v1/services/$SERVICE_ID/env-vars/IG_SESSION_B64" \
  -d "$(jq -nc --arg v "$session_b64" '{value:$v}')" >/dev/null

deploy_json="$(curl -sS -X POST \
  -H "Authorization: Bearer $RENDER_API_KEY" \
  -H 'Accept: application/json' \
  -H 'Content-Type: application/json' \
  "https://api.render.com/v1/services/$SERVICE_ID/deploys" \
  -d '{"clearCache":"do_not_clear"}')"

deploy_id="$(echo "$deploy_json" | jq -r '.id // .deploy.id // empty')"
if [[ -z "$deploy_id" ]]; then
  echo "No se pudo crear deploy. Respuesta:" >&2
  echo "$deploy_json" >&2
  exit 1
fi

echo "Session actualizada. Deploy iniciado: $deploy_id"
echo "Service: $SERVICE_ID"
