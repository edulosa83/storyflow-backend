import base64
import os
import re
from time import sleep
from typing import List, Literal, Optional
from urllib.parse import quote, urlparse

import instaloader
import requests
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, Field

app = FastAPI(title="StoryFlow Backend", version="1.1.0")


class ResolveRequest(BaseModel):
    input: str = Field(min_length=1, max_length=500)


class StoryMediaOut(BaseModel):
    id: str
    username: str
    mediaType: Literal["image", "video"]
    thumbnailUrl: str
    downloadUrl: str
    takenAtIso: Optional[str] = None


class ResolveResponse(BaseModel):
    normalizedInput: str
    source: str = "instaloader"
    items: List[StoryMediaOut]


_URL_REGEX = re.compile(r"https?://[^\s]+", re.IGNORECASE)
_RESERVED_SEGMENTS = {
    "p",
    "reel",
    "reels",
    "tv",
    "explore",
    "accounts",
    "stories",
    "direct",
    "share",
    "about",
}
_SHORTCODE_SEGMENTS = {"p", "reel", "reels", "tv"}
_INSTAGRAM_HOSTS = {"instagram.com", "www.instagram.com", "instagr.am", "www.instagr.am"}
_SESSION_B64_TEMP_PATH = "/tmp/instaloader-session"
_AUTH_ERROR_HINTS = (
    "please wait a few minutes",
    "challenge_required",
    "checkpoint_required",
    "login_required",
    "forbidden",
    "unauthorized",
    "429",
)
_IGSAVER_ENDPOINT = "https://igsaver.io/api/download"
_IGSAVER_CONNECT_TIMEOUT_SEC = 8
_IGSAVER_READ_TIMEOUT_SEC = 25
_IGSAVER_RETRIES = 1

SessionState = Literal["ok", "invalid", "rate_limited", "challenge", "connection_error"]


def normalize_input(raw_input: str) -> str:
    cleaned = raw_input.strip()
    if not cleaned:
        return ""

    url_match = _URL_REGEX.search(cleaned)
    if url_match:
        url = url_match.group(0)
        username = extract_username_from_instagram_url(url)
        return username if username else url

    return cleaned.removeprefix("@").strip()


def extract_username_from_instagram_url(url: str) -> Optional[str]:
    parsed = urlparse(url)
    host = (parsed.netloc or "").lower()
    if host.startswith("www."):
        host = host[4:]

    if host not in {"instagram.com", "instagr.am"}:
        return None

    segments = [segment for segment in parsed.path.split("/") if segment]
    if not segments:
        return None

    if segments[0].lower() == "stories" and len(segments) > 1:
        return segments[1]

    candidate = segments[0]
    if candidate.lower() in _RESERVED_SEGMENTS:
        return None

    return candidate


def is_url(value: str) -> bool:
    return bool(_URL_REGEX.match(value.strip()))


def is_instagram_host(host: str) -> bool:
    return host.lower() in _INSTAGRAM_HOSTS


def extract_shortcode_from_instagram_url(url: str) -> Optional[str]:
    parsed = urlparse(url)
    host = (parsed.netloc or "").lower()
    if not is_instagram_host(host):
        return None

    segments = [segment for segment in parsed.path.split("/") if segment]
    if len(segments) < 2:
        return None

    if segments[0].lower() in _SHORTCODE_SEGMENTS:
        return segments[1]

    return None


def resolve_redirected_instagram_url(url: str) -> Optional[str]:
    try:
        response = requests.get(
            url,
            allow_redirects=True,
            timeout=12,
            headers={"User-Agent": "Mozilla/5.0 (StoryFlow)"},
        )
        return response.url
    except Exception:  # noqa: BLE001
        return None


def resolve_username_from_instagram_resource(url: str, loader: instaloader.Instaloader) -> Optional[str]:
    username = extract_username_from_instagram_url(url)
    if username:
        return username

    shortcode = extract_shortcode_from_instagram_url(url)
    if not shortcode:
        redirected_url = resolve_redirected_instagram_url(url)
        if redirected_url:
            username = extract_username_from_instagram_url(redirected_url)
            if username:
                return username
            shortcode = extract_shortcode_from_instagram_url(redirected_url)

    if not shortcode:
        return None

    post = instaloader.Post.from_shortcode(loader.context, shortcode)
    return post.owner_username


def classify_session_exception(exc: Exception) -> SessionState:
    message = str(exc).lower()

    if isinstance(exc, instaloader.exceptions.TooManyRequestsException):
        return "rate_limited"

    if isinstance(exc, instaloader.exceptions.LoginRequiredException):
        return "invalid"

    if isinstance(exc, instaloader.exceptions.QueryReturnedForbiddenException):
        if "challenge_required" in message or "checkpoint_required" in message:
            return "challenge"
        return "invalid"

    if isinstance(exc, instaloader.exceptions.ConnectionException):
        if "please wait a few minutes" in message or "too many requests" in message or "429" in message:
            return "rate_limited"
        if "challenge_required" in message or "checkpoint_required" in message:
            return "challenge"
        if "login_required" in message or "forbidden" in message or "unauthorized" in message:
            return "invalid"
        return "connection_error"

    if isinstance(exc, instaloader.exceptions.LoginException):
        if "challenge_required" in message or "checkpoint_required" in message:
            return "challenge"
        return "invalid"

    return "connection_error"


def map_session_state_to_http_error(state: SessionState) -> HTTPException:
    if state == "rate_limited":
        return HTTPException(
            status_code=503,
            detail="Instagram limitó temporalmente la sesión. Espera unos minutos e intenta de nuevo.",
        )
    if state == "challenge":
        return HTTPException(
            status_code=503,
            detail="Instagram pidió verificación de seguridad (checkpoint/challenge). Renueva la sesión del backend.",
        )
    if state == "invalid":
        return HTTPException(
            status_code=503,
            detail="La sesión de Instagram expiró o ya no es válida. Renueva la sesión del backend.",
        )
    return HTTPException(
        status_code=502,
        detail="No se pudo validar la sesión de Instagram por un problema de conexión.",
    )


def probe_session(loader: instaloader.Instaloader) -> SessionState:
    try:
        return "ok" if loader.test_login() else "invalid"
    except Exception as exc:  # noqa: BLE001
        return classify_session_exception(exc)


def try_login_with_password(
    loader: instaloader.Instaloader,
    username: str,
    password: str,
    session_file: str,
) -> bool:
    try:
        loader.login(username, password)
        try:
            loader.save_session_to_file(session_file)
        except Exception:  # noqa: BLE001
            pass
        return probe_session(loader) == "ok"
    except Exception:  # noqa: BLE001
        return False


def has_instaloader_config() -> bool:
    username = os.getenv("IG_USERNAME", "").strip()
    session_file = os.getenv("IG_SESSION_FILE", "").strip()
    session_b64 = os.getenv("IG_SESSION_B64", "").strip()
    return bool(username and (session_file or session_b64))


def build_loader() -> instaloader.Instaloader:
    loader = instaloader.Instaloader(
        sleep=False,
        quiet=True,
        download_pictures=False,
        download_videos=False,
        save_metadata=False,
        compress_json=False,
        request_timeout=45.0,
    )

    username = os.getenv("IG_USERNAME", "").strip()
    password = os.getenv("IG_PASSWORD", "").strip()
    session_file = os.getenv("IG_SESSION_FILE", "").strip()
    session_b64 = os.getenv("IG_SESSION_B64", "").strip()

    if not username:
        raise HTTPException(status_code=500, detail="Configura IG_USERNAME en el entorno del backend.")

    if not session_file and session_b64:
        session_file = _SESSION_B64_TEMP_PATH

    if session_b64:
        try:
            decoded = base64.b64decode(session_b64)
            with open(session_file, "wb") as handle:
                handle.write(decoded)
        except Exception as exc:  # noqa: BLE001
            raise HTTPException(status_code=500, detail=f"No se pudo decodificar IG_SESSION_B64: {exc}") from exc

    if not session_file:
        raise HTTPException(status_code=500, detail="Configura IG_SESSION_FILE o IG_SESSION_B64 en el entorno del backend.")

    if not os.path.exists(session_file):
        raise HTTPException(status_code=500, detail=f"No se encontró el archivo de sesión: {session_file}")

    try:
        loader.load_session_from_file(username=username, filename=session_file)
    except Exception as exc:  # noqa: BLE001
        raise HTTPException(status_code=500, detail=f"No se pudo cargar la sesión de Instagram: {exc}") from exc

    session_state = probe_session(loader)
    if session_state != "ok" and password:
        if try_login_with_password(loader, username, password, session_file):
            session_state = "ok"
        else:
            session_state = probe_session(loader)

    if session_state != "ok":
        raise map_session_state_to_http_error(session_state)

    return loader


def is_story_like_url(url: str) -> bool:
    parsed = urlparse(url)
    segments = [segment.lower() for segment in parsed.path.split("/") if segment]
    if not segments:
        return False
    if segments[0] == "stories":
        return True
    return segments[0] not in _SHORTCODE_SEGMENTS


def make_igsaver_target(raw_input: str) -> tuple[str, str]:
    cleaned = raw_input.strip()
    if not cleaned:
        raise HTTPException(status_code=400, detail="Input inválido")

    if is_url(cleaned):
        parsed = urlparse(cleaned)
        if not is_instagram_host((parsed.netloc or "").lower()):
            raise HTTPException(status_code=400, detail="La URL no es de Instagram")

        redirected_url = resolve_redirected_instagram_url(cleaned) or cleaned
        if is_story_like_url(redirected_url):
            username = extract_username_from_instagram_url(redirected_url)
            if not username:
                raise HTTPException(status_code=400, detail="No se pudo obtener el usuario desde la URL")
            return f"https://www.instagram.com/stories/{username}/", username

        # Reel/post URLs are passed as-is so provider can resolve media directly.
        username = extract_username_from_instagram_url(redirected_url) or ""
        return redirected_url, username

    username = cleaned.removeprefix("@").strip()
    if not username:
        raise HTTPException(status_code=400, detail="Input inválido")
    return f"https://www.instagram.com/stories/{username}/", username


def parse_igsaver_items(payload: dict, fallback_username: str) -> List[StoryMediaOut]:
    raw_items = payload.get("items") if isinstance(payload, dict) else None
    if not isinstance(raw_items, list):
        return []

    username = (
        (payload.get("user") or {}).get("username") if isinstance(payload.get("user"), dict) else None
    )
    username = (username or fallback_username or "instagram").strip()

    items: List[StoryMediaOut] = []
    for idx, item in enumerate(raw_items):
        if not isinstance(item, dict):
            continue

        media_url = (item.get("media_url") or "").strip()
        thumb_url = (item.get("thumbnail_url") or media_url).strip()
        if not media_url.startswith("http") or not thumb_url.startswith("http"):
            continue

        media_type = (item.get("media_type") or "").strip().lower()
        is_video = media_type in {"video", "reel", "clip"}
        media_id = (str(item.get("id") or "").strip()) or f"{username}_{idx}_{abs(hash(media_url))}"

        items.append(
            StoryMediaOut(
                id=media_id,
                username=username,
                mediaType="video" if is_video else "image",
                thumbnailUrl=thumb_url,
                downloadUrl=media_url,
                takenAtIso=None,
            )
        )

    return items


def resolve_with_igsaver(raw_input: str) -> ResolveResponse:
    target_url, normalized_username = make_igsaver_target(raw_input)
    encoded_url = quote(target_url, safe="")
    endpoint = f"{_IGSAVER_ENDPOINT}?url={encoded_url}"

    last_error: Optional[Exception] = None
    response: Optional[requests.Response] = None

    for attempt in range(_IGSAVER_RETRIES + 1):
        try:
            response = requests.get(
                endpoint,
                timeout=(_IGSAVER_CONNECT_TIMEOUT_SEC, _IGSAVER_READ_TIMEOUT_SEC),
                headers={
                    "User-Agent": "StoryFlow/1.1 (+https://storyflow.app)",
                    "Accept": "application/json",
                },
            )
            break
        except requests.Timeout as exc:
            last_error = exc
            if attempt < _IGSAVER_RETRIES:
                sleep(0.8)
                continue
            raise HTTPException(status_code=504, detail="El proveedor externo tardó demasiado en responder.") from exc
        except requests.RequestException as exc:
            last_error = exc
            if attempt < _IGSAVER_RETRIES:
                sleep(0.8)
                continue
            raise HTTPException(status_code=502, detail="No se pudo conectar al proveedor externo.") from exc

    if response is None:
        raise HTTPException(status_code=502, detail=f"Fallo de red: {last_error}")

    try:
        data = response.json()
    except Exception as exc:  # noqa: BLE001
        raise HTTPException(status_code=502, detail="Respuesta inválida del proveedor externo.") from exc

    if response.status_code >= 400:
        detail = str((data or {}).get("error") or "").strip() if isinstance(data, dict) else ""
        if "scraper error 400" in detail.lower():
            raise HTTPException(status_code=404, detail="No se encontraron stories activas para este perfil.")
        raise HTTPException(status_code=502, detail=detail or "Error del proveedor externo.")

    items = parse_igsaver_items(data if isinstance(data, dict) else {}, normalized_username)
    source_type = (data.get("type") if isinstance(data, dict) else None) or "stories_collection"

    if not items:
        # For stories resolver, empty means no active stories.
        if source_type in {"stories_collection", "profile", "story"}:
            raise HTTPException(status_code=404, detail="No se encontraron stories activas para este perfil.")
        raise HTTPException(status_code=404, detail="No se encontró contenido descargable en esa URL.")

    resolved_input = normalized_username or items[0].username or normalize_input(raw_input)
    return ResolveResponse(
        normalizedInput=resolved_input,
        source=f"igsaver:{source_type}",
        items=items,
    )


def resolve_with_instaloader(raw_input: str) -> ResolveResponse:
    normalized = normalize_input(raw_input)
    if not normalized:
        raise HTTPException(status_code=400, detail="Input inválido")

    loader = build_loader()

    try:
        target_username = normalized
        if is_url(normalized):
            parsed = urlparse(normalized)
            if not is_instagram_host((parsed.netloc or "").lower()):
                raise HTTPException(status_code=400, detail="La URL no es de Instagram")

            target_username = resolve_username_from_instagram_resource(normalized, loader) or ""
            if not target_username:
                raise HTTPException(status_code=404, detail="No se pudo resolver el usuario desde esa URL de Instagram")

        profile = instaloader.Profile.from_username(loader.context, target_username)
        user_id = profile.userid

        items: List[StoryMediaOut] = []

        for story in loader.get_stories(userids=[user_id]):
            for item in story.get_items():
                media_type: Literal["image", "video"] = "video" if item.is_video else "image"
                download_url = item.video_url if item.is_video and item.video_url else item.url
                thumbnail_url = item.url
                taken_at = item.date_utc.isoformat() if item.date_utc else None

                items.append(
                    StoryMediaOut(
                        id=str(item.mediaid),
                        username=item.owner_username,
                        mediaType=media_type,
                        thumbnailUrl=thumbnail_url,
                        downloadUrl=download_url,
                        takenAtIso=taken_at,
                    )
                )

        items.sort(key=lambda x: x.takenAtIso or "", reverse=True)

        return ResolveResponse(
            normalizedInput=target_username,
            source="instaloader",
            items=items,
        )

    except instaloader.exceptions.ProfileNotExistsException as exc:
        probe = probe_session(loader)
        if probe != "ok":
            raise map_session_state_to_http_error(probe) from exc
        message = str(exc).lower()
        if any(hint in message for hint in _AUTH_ERROR_HINTS):
            raise HTTPException(
                status_code=503,
                detail="Instagram bloqueó temporalmente la consulta. Intenta de nuevo en unos minutos.",
            ) from exc
        raise HTTPException(status_code=404, detail="El perfil no existe o cambió de usuario") from exc
    except instaloader.exceptions.QueryReturnedForbiddenException as exc:
        state = classify_session_exception(exc)
        raise map_session_state_to_http_error(state) from exc
    except instaloader.exceptions.TooManyRequestsException as exc:
        raise HTTPException(
            status_code=503,
            detail="Instagram limitó temporalmente la sesión. Espera unos minutos e intenta de nuevo.",
        ) from exc
    except instaloader.exceptions.LoginRequiredException as exc:
        raise HTTPException(status_code=503, detail="La sesión de Instagram no es válida") from exc
    except instaloader.exceptions.ConnectionException as exc:
        state = classify_session_exception(exc)
        if state in {"rate_limited", "challenge", "invalid"}:
            raise map_session_state_to_http_error(state) from exc
        raise HTTPException(status_code=502, detail=f"Error de conexión con Instagram: {exc}") from exc
    except Exception as exc:  # noqa: BLE001
        raise HTTPException(status_code=500, detail=f"Error inesperado: {exc}") from exc


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "ok"}


@app.post("/api/v1/stories/resolve", response_model=ResolveResponse)
def resolve_stories(payload: ResolveRequest) -> ResolveResponse:
    mode = os.getenv("STORYFLOW_RESOLVER_MODE", "external").strip().lower()

    if mode == "instaloader":
        return resolve_with_instaloader(payload.input)

    if mode == "hybrid":
        try:
            return resolve_with_igsaver(payload.input)
        except HTTPException as external_exc:
            # If we cannot resolve via external provider, try instaloader when configured.
            if has_instaloader_config():
                try:
                    return resolve_with_instaloader(payload.input)
                except HTTPException:
                    raise external_exc
            raise external_exc

    # default: external (no Instagram account required on backend)
    return resolve_with_igsaver(payload.input)
