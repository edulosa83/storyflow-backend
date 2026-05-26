# StoryFlow Backend (FastAPI + Instaloader)

Backend JSON para la app Android, apto para despliegue en nube.

## Endpoint

- `POST /api/v1/stories/resolve`

Request:

```json
{ "input": "usuario_o_url" }
```

## Variables de entorno

Resolver (opcional):

- `STORYFLOW_RESOLVER_MODE`:
  - `external` (default): usa proveedor externo público, no requiere cuenta de Instagram.
  - `hybrid`: intenta proveedor externo y si falla, usa Instaloader si hay sesión configurada.
  - `instaloader`: usa solo Instaloader.

Solo para `hybrid`/`instaloader`:

- `IG_USERNAME`
- Uno de estos dos:
  - `IG_SESSION_FILE`
  - `IG_SESSION_B64`
- `IG_PASSWORD` (opcional) para reintento de login automático si la sesión cargada expiró.

`IG_SESSION_B64` es ideal para Railway/Render porque evita montar archivos, pero solo aplica cuando activas modo `instaloader`/`hybrid`.

## Local

```bash
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
cp .env.example .env
set -a
source .env
set +a
uvicorn app:app --host 0.0.0.0 --port 8080
```

## Docker

Desde `backend/`:

```bash
docker build -t storyflow-backend .
docker run --rm -p 8080:8080 \
  -e IG_USERNAME=tu_usuario \
  -e IG_SESSION_B64="$(../scripts/make_session_b64.sh)" \
  storyflow-backend
```

Health check:

```bash
curl http://127.0.0.1:8080/health
```

## Notas operativas

- En modo `external` no dependes de tu cuenta personal de Instagram.
- `downloadUrl` puede expirar, así que conviene descargar en cuanto se listan las stories.
- Si decides usar `instaloader`/`hybrid`, la sesión puede expirar y tendrás que renovarla:
  - `./scripts/renew_instagram_session.sh`
  - `RENDER_API_KEY=... ./scripts/refresh_render_session.sh`
