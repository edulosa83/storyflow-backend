# StoryFlow (Android + Backend)

StoryFlow descarga stories con flujo mejorado:

- Recibe link directo desde `Compartir` en Instagram.
- Normaliza usuario/URL automáticamente.
- Lista stories activas desde backend propio.
- Descarga en `Descargas/StoryFlow`.

## Lo importante para tu caso

Para que funcione sin conectar el celular a tu compu, el backend debe estar en nube (URL pública HTTPS).

La app ya quedó preparada para eso:

- Usa URL fija del backend desde código (`BuildConfig.BACKEND_BASE_URL`).
- Puedes compilar con URL fija usando `-PSTORYFLOW_BACKEND_URL=...`.

## 1) App Android

### Compilar

```bash
./gradlew :app:assembleDebug
```

### Compilar con URL fija del backend

```bash
./gradlew :app:assembleRelease -PSTORYFLOW_BACKEND_URL=https://tu-backend-publico.com/
```

## 2) Backend en nube (recomendado)

El backend está en `backend/` y trae `Dockerfile`.

### Variables necesarias

- Con el backend actual (modo `external` por default), no necesitas cuenta de Instagram en el servidor.
- Si quieres modo `instaloader` o `hybrid`, entonces sí usas:
  - `IG_USERNAME`
  - `IG_SESSION_B64` (recomendado en nube)
  - opcional `IG_PASSWORD`

Genera `IG_SESSION_B64` desde tu sesión local:

```bash
./scripts/make_session_b64.sh
```

### Render / Railway (resumen)

1. Crea un servicio web desde este repo usando `backend/Dockerfile`.
2. (Opcional) Configura variables solo si vas a usar `instaloader`/`hybrid`.
3. Verifica `GET /health`.
4. Copia la URL pública HTTPS.
5. Compila la app con esa URL (`-PSTORYFLOW_BACKEND_URL=...`) e instala el APK.

Si usas `instaloader`/`hybrid` y ves error `503` con mensaje de sesión/challenge:

1. Renueva la sesión de Instagram.
2. Regenera `IG_SESSION_B64`.
3. Actualiza la variable en Render/Railway y redeploy.

Atajo para Render:

```bash
RENDER_API_KEY=tu_api_key ./scripts/refresh_render_session.sh
```

Flujo completo recomendado:

```bash
./scripts/renew_instagram_session.sh
RENDER_API_KEY=tu_api_key ./scripts/refresh_render_session.sh
```

## 3) Flujo local por USB (solo debug)

Si quieres volver a usar backend local:

```bash
./scripts/start_backend.sh
./scripts/connect_device.sh R5CWC3Y2VSH
```

Eso depende de ADB/USB y se pierde al desconectar.

## 4) Estructura

- `app/` cliente Android
- `backend/` API FastAPI + Instaloader
- `scripts/` utilidades de arranque y sesión
