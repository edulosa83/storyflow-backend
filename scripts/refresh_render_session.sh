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

render_api() {
  local method="$1"
  local url="$2"
  local data="${3:-}"
  local tmp status
  tmp="$(mktemp)"
  if [[ -n "$data" ]]; then
    status="$(curl -sS -o "$tmp" -w "%{http_code}" -X "$method" \
      -H "Authorization: Bearer $RENDER_API_KEY" \
      -H 'Accept: application/json' \
      -H 'Content-Type: application/json' \
      "$url" \
      -d "$data")"
  else
    status="$(curl -sS -o "$tmp" -w "%{http_code}" -X "$method" \
      -H "Authorization: Bearer $RENDER_API_KEY" \
      -H 'Accept: application/json' \
      "$url")"
  fi
  cat "$tmp"
  rm -f "$tmp"
  echo
  echo "__STATUS__:$status"
}

if [[ -z "$SERVICE_ID" ]]; then
  services_resp="$(render_api GET 'https://api.render.com/v1/services?limit=100')"
  services_json="$(echo "$services_resp" | sed '/^__STATUS__:/d')"
  services_status="$(echo "$services_resp" | sed -n 's/^__STATUS__://p')"

  if [[ "$services_status" -lt 200 || "$services_status" -ge 300 ]]; then
    if [[ "$services_status" == "401" || "$services_status" == "403" ]]; then
      echo "RENDER_API_KEY inválida o sin permisos. HTTP $services_status" >&2
    else
      echo "Error consultando servicios en Render. HTTP $services_status" >&2
    fi
    echo "$services_json" >&2
    exit 1
  fi

  SERVICE_ID="$(echo "$services_json" | jq -r "if type==\"array\" then (.[]?.service? | select(.name==\"$SERVICE_NAME\") | .id) else empty end" | head -n1)"
  if [[ -z "$SERVICE_ID" ]]; then
    SERVICE_ID="$(echo "$services_json" | jq -r "if type==\"array\" then (.[]? | select(.name==\"$SERVICE_NAME\") | .id) else empty end" | head -n1)"
  fi
fi

if [[ -z "$SERVICE_ID" ]]; then
  echo "No se encontró SERVICE_ID para SERVICE_NAME=$SERVICE_NAME" >&2
  exit 1
fi

session_b64="$(base64 < "$SESSION_FILE" | tr -d '\n')"

update_resp="$(render_api PUT "https://api.render.com/v1/services/$SERVICE_ID/env-vars/IG_SESSION_B64" "$(jq -nc --arg v "$session_b64" '{value:$v}')")"
update_json="$(echo "$update_resp" | sed '/^__STATUS__:/d')"
update_status="$(echo "$update_resp" | sed -n 's/^__STATUS__://p')"

if [[ "$update_status" -lt 200 || "$update_status" -ge 300 ]]; then
  echo "No se pudo actualizar IG_SESSION_B64 en Render. HTTP $update_status" >&2
  echo "$update_json" >&2
  exit 1
fi

deploy_resp="$(render_api POST "https://api.render.com/v1/services/$SERVICE_ID/deploys" '{"clearCache":"do_not_clear"}')"
deploy_json="$(echo "$deploy_resp" | sed '/^__STATUS__:/d')"
deploy_status="$(echo "$deploy_resp" | sed -n 's/^__STATUS__://p')"

if [[ "$deploy_status" -lt 200 || "$deploy_status" -ge 300 ]]; then
  echo "No se pudo crear deploy en Render. HTTP $deploy_status" >&2
  echo "$deploy_json" >&2
  exit 1
fi

deploy_id="$(echo "$deploy_json" | jq -r '.id // .deploy.id // empty')"
if [[ -z "$deploy_id" ]]; then
  echo "No se pudo crear deploy. Respuesta:" >&2
  echo "$deploy_json" >&2
  exit 1
fi

echo "Session actualizada. Deploy iniciado: $deploy_id"
echo "Service: $SERVICE_ID"
