# StoryFlow Backend (FastAPI + Instaloader)

Backend JSON para la app Android, apto para despliegue en nube.

## Endpoint

- `POST /api/v1/stories/resolve`

Request:

```json
{ "input": "usuario_o_url" }
```

## Variables de entorno

Obligatorias:

- `IG_USERNAME`
- Uno de estos dos:
  - `IG_SESSION_FILE`
  - `IG_SESSION_B64`

Opcional:

- `IG_PASSWORD` para reintento de login automático si la sesión cargada expiró.

`IG_SESSION_B64` es ideal para Railway/Render porque evita montar archivos.

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

- La sesión de Instagram expira; cuando falle, recaptura sesión y actualiza `IG_SESSION_B64`.
- `downloadUrl` puede expirar, así que conviene descargar en cuanto se listan las stories.
- Si Instagram limita temporalmente la cuenta, el endpoint responde `503` con detalle para que la app muestre un mensaje claro.
