#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

require() {
  local name="$1"
  if [[ -z "${!name:-}" ]]; then
    echo "Falta variable: $name" >&2
    exit 1
  fi
}

require GITHUB_TOKEN
require RENDER_API_KEY
require IG_USERNAME

REPO_NAME="${REPO_NAME:-storyflow-backend}"
REPO_PRIVATE="${REPO_PRIVATE:-true}"
REPO_DESCRIPTION="${REPO_DESCRIPTION:-StoryFlow backend and Android app}"
GIT_BRANCH="${GIT_BRANCH:-main}"
RENDER_SERVICE_NAME="${RENDER_SERVICE_NAME:-storyflow-backend}"
RENDER_REGION="${RENDER_REGION:-oregon}"
RENDER_PLAN="${RENDER_PLAN:-free}"
SESSION_FILE="${SESSION_FILE:-$HOME/.config/instaloader/session-storyflow-bootstrap}"

if [[ -z "${IG_SESSION_B64:-}" ]]; then
  if [[ ! -f "$SESSION_FILE" ]]; then
    echo "No existe SESSION_FILE y no diste IG_SESSION_B64: $SESSION_FILE" >&2
    exit 1
  fi
  IG_SESSION_B64="$(base64 < "$SESSION_FILE" | tr -d '\n')"
fi

if [[ ! -d .git ]]; then
  git init
fi

if [[ -z "$(git config --get user.name || true)" ]]; then
  git config user.name "StoryFlow Bot"
fi
if [[ -z "$(git config --get user.email || true)" ]]; then
  git config user.email "storyflow-bot@example.com"
fi

git add .
if ! git diff --cached --quiet; then
  git commit -m "Prepare StoryFlow for Render deployment"
fi

echo "[1/7] Creating GitHub repo $REPO_NAME"
create_repo_resp="$(curl -sS -X POST \
  -H 'Accept: application/vnd.github+json' \
  -H "Authorization: Bearer $GITHUB_TOKEN" \
  -H 'X-GitHub-Api-Version: 2022-11-28' \
  https://api.github.com/user/repos \
  -d "$(jq -nc --arg n "$REPO_NAME" --arg d "$REPO_DESCRIPTION" --argjson p "$REPO_PRIVATE" '{name:$n, description:$d, private:$p}')")"

repo_full_name="$(echo "$create_repo_resp" | jq -r '.full_name // empty')"
repo_html_url="$(echo "$create_repo_resp" | jq -r '.html_url // empty')"

if [[ -z "$repo_full_name" ]]; then
  msg="$(echo "$create_repo_resp" | jq -r '.message // .errors[0].message // empty')"
  if [[ "$msg" == *"name already exists"* || "$msg" == *"already exists"* || "$msg" == *"Repository creation failed."* ]]; then
    echo "Repo ya existe; recuperando datos"
    user_login="$(curl -sS -H "Authorization: Bearer $GITHUB_TOKEN" -H 'Accept: application/vnd.github+json' https://api.github.com/user | jq -r '.login')"
    repo_full_name="$user_login/$REPO_NAME"
    repo_html_url="https://github.com/$repo_full_name"
  else
    echo "Error creando repo: $create_repo_resp" >&2
    exit 1
  fi
fi

if [[ -z "$repo_full_name" ]]; then
  echo "No se pudo determinar full_name del repo" >&2
  exit 1
fi

repo_push_url="https://x-access-token:${GITHUB_TOKEN}@github.com/${repo_full_name}.git"

if git remote get-url origin >/dev/null 2>&1; then
  git remote remove origin
fi
git remote add origin "https://github.com/${repo_full_name}.git"

echo "[2/7] Pushing code to GitHub"
git push "$repo_push_url" HEAD:"$GIT_BRANCH" --force

if ! git symbolic-ref refs/remotes/origin/HEAD >/dev/null 2>&1; then
  git remote set-head origin -a || true
fi

echo "[3/7] Getting Render workspace"
owners_resp="$(curl -sS -H "Authorization: Bearer $RENDER_API_KEY" -H 'Accept: application/json' 'https://api.render.com/v1/owners?limit=20')"
owner_id="$(echo "$owners_resp" | jq -r '.[]?.owner.id // empty' | head -n1)"
if [[ -z "$owner_id" ]]; then
  owner_id="$(echo "$owners_resp" | jq -r '.[0].owner.id // empty')"
fi
if [[ -z "$owner_id" ]]; then
  echo "No pude obtener ownerId en Render: $owners_resp" >&2
  exit 1
fi

echo "[4/7] Creating Render service $RENDER_SERVICE_NAME"
create_service_payload="$(jq -nc \
  --arg type "web_service" \
  --arg name "$RENDER_SERVICE_NAME" \
  --arg ownerId "$owner_id" \
  --arg repo "$repo_html_url" \
  --arg branch "$GIT_BRANCH" \
  --arg autoDeploy "yes" \
  --arg plan "$RENDER_PLAN" \
  --arg region "$RENDER_REGION" \
  --arg dockerContext "backend" \
  --arg dockerfilePath "backend/Dockerfile" \
  --arg igUser "$IG_USERNAME" \
  --arg igSession "$IG_SESSION_B64" \
  '{
    type:$type,
    name:$name,
    ownerId:$ownerId,
    repo:$repo,
    branch:$branch,
    autoDeploy:$autoDeploy,
    envVars:[
      {key:"IG_USERNAME", value:$igUser},
      {key:"IG_SESSION_B64", value:$igSession}
    ],
    serviceDetails:{
      runtime:"docker",
      env:"docker",
      plan:$plan,
      region:$region,
      envSpecificDetails:{
        dockerContext:$dockerContext,
        dockerfilePath:$dockerfilePath
      }
    }
  }')"

create_service_resp="$(curl -sS -X POST \
  -H "Authorization: Bearer $RENDER_API_KEY" \
  -H 'Accept: application/json' \
  -H 'Content-Type: application/json' \
  https://api.render.com/v1/services \
  -d "$create_service_payload")"

service_id="$(echo "$create_service_resp" | jq -r '.id // empty')"
if [[ -z "$service_id" ]]; then
  msg="$(echo "$create_service_resp" | jq -r '.message // .errors[0].message // empty')"
  if [[ "$msg" == *"name already exists"* || "$msg" == *"already exists"* ]]; then
    echo "Service ya existe, buscando por nombre"
    list_services_resp="$(curl -sS -H "Authorization: Bearer $RENDER_API_KEY" -H 'Accept: application/json' "https://api.render.com/v1/services?name=${RENDER_SERVICE_NAME}&limit=20")"
    service_id="$(echo "$list_services_resp" | jq -r '.[]?.service.id // empty' | head -n1)"
    if [[ -z "$service_id" ]]; then
      service_id="$(echo "$list_services_resp" | jq -r '.[0].service.id // empty')"
    fi
  else
    echo "Error creando servicio: $create_service_resp" >&2
    exit 1
  fi
fi

if [[ -z "$service_id" ]]; then
  echo "No se pudo determinar service_id" >&2
  exit 1
fi

echo "[5/7] Waiting for first successful deploy"
max_tries=90
sleep_secs=10
for ((i=1; i<=max_tries; i++)); do
  deploys_resp="$(curl -sS -H "Authorization: Bearer $RENDER_API_KEY" -H 'Accept: application/json' "https://api.render.com/v1/services/${service_id}/deploys?limit=5")"
  status="$(echo "$deploys_resp" | jq -r '.[0].deploy.status // .[0].status // empty')"
  if [[ "$status" == "live" ]]; then
    echo "Deploy live"
    break
  fi
  if [[ "$status" == "build_failed" || "$status" == "update_failed" || "$status" == "pre_deploy_failed" || "$status" == "canceled" || "$status" == "deactivated" ]]; then
    echo "Deploy falló con estado: $status" >&2
    echo "$deploys_resp" >&2
    exit 1
  fi
  echo "Estado deploy: ${status:-unknown} (intento $i/$max_tries)"
  sleep "$sleep_secs"
  if [[ "$i" -eq "$max_tries" ]]; then
    echo "Timeout esperando deploy live" >&2
    exit 1
  fi
done

service_resp="$(curl -sS -H "Authorization: Bearer $RENDER_API_KEY" -H 'Accept: application/json' "https://api.render.com/v1/services/${service_id}")"
service_url="$(echo "$service_resp" | jq -r '.service.serviceDetails.url // .serviceDetails.url // empty')"
if [[ -z "$service_url" ]]; then
  service_url="$(echo "$service_resp" | jq -r '.serviceDetails.url // empty')"
fi

if [[ -z "$service_url" ]]; then
  echo "No se pudo obtener URL pública del servicio" >&2
  echo "$service_resp" >&2
  exit 1
fi

if [[ "$service_url" != */ ]]; then
  service_url="$service_url/"
fi

echo "[6/7] Backend URL: $service_url"

echo "[7/7] Compiling Android app with fixed backend URL"
./gradlew :app:assembleDebug -x lint -PSTORYFLOW_BACKEND_URL="$service_url"

cat <<MSG

DEPLOY COMPLETO
- GitHub repo: $repo_html_url
- Render service id: $service_id
- Backend URL: $service_url
- APK: app/build/outputs/apk/debug/app-debug.apk

Para instalar en un dispositivo Android conectado:
adb install -r app/build/outputs/apk/debug/app-debug.apk
MSG
