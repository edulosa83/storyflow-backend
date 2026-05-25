import base64
import os
import re
from typing import List, Literal, Optional
from urllib.parse import urlparse

import instaloader
import requests
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, Field

app = FastAPI(title="StoryFlow Backend", version="1.0.0")


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
    "p", "reel", "reels", "tv", "explore", "accounts", "stories", "direct", "share", "about"
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

    if segments[0] == "stories" and len(segments) > 1:
        return segments[1]

    candidate = segments[0]
    if candidate in _RESERVED_SEGMENTS:
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
            detail="Instagram limitó temporalmente la sesión. Espera unos minutos e intenta de nuevo."
        )
    if state == "challenge":
        return HTTPException(
            status_code=503,
            detail="Instagram pidió verificación de seguridad (checkpoint/challenge). Renueva la sesión del backend."
        )
    if state == "invalid":
        return HTTPException(
            status_code=503,
            detail="La sesión de Instagram expiró o ya no es válida. Renueva la sesión del backend."
        )
    return HTTPException(
        status_code=502,
        detail="No se pudo validar la sesión de Instagram por un problema de conexión."
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
            # If session persistence fails we can still continue in-memory for this process.
            pass
        return probe_session(loader) == "ok"
    except Exception:  # noqa: BLE001
        return False


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
        raise HTTPException(
            status_code=500,
            detail="Configura IG_USERNAME en el entorno del backend."
        )

    if not session_file and session_b64:
        session_file = _SESSION_B64_TEMP_PATH

    if session_b64:
        try:
            decoded = base64.b64decode(session_b64)
            with open(session_file, "wb") as handle:
                handle.write(decoded)
        except Exception as exc:  # noqa: BLE001
            raise HTTPException(
                status_code=500,
                detail=f"No se pudo decodificar IG_SESSION_B64: {exc}"
            ) from exc

    if not session_file:
        raise HTTPException(
            status_code=500,
            detail="Configura IG_SESSION_FILE o IG_SESSION_B64 en el entorno del backend."
        )

    if not os.path.exists(session_file):
        raise HTTPException(
            status_code=500,
            detail=f"No se encontró el archivo de sesión: {session_file}"
        )

    try:
        loader.load_session_from_file(username=username, filename=session_file)
    except Exception as exc:  # noqa: BLE001
        raise HTTPException(
            status_code=500,
            detail=f"No se pudo cargar la sesión de Instagram: {exc}"
        ) from exc

    session_state = probe_session(loader)
    if session_state != "ok" and password:
        if try_login_with_password(loader, username, password, session_file):
            session_state = "ok"
        else:
            session_state = probe_session(loader)

    if session_state != "ok":
        raise map_session_state_to_http_error(session_state)

    return loader


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "ok"}


@app.post("/api/v1/stories/resolve", response_model=ResolveResponse)
def resolve_stories(payload: ResolveRequest) -> ResolveResponse:
    normalized = normalize_input(payload.input)
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
                raise HTTPException(
                    status_code=404,
                    detail="No se pudo resolver el usuario desde esa URL de Instagram"
                )

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
                detail="Instagram bloqueó temporalmente la consulta. Intenta de nuevo en unos minutos."
            ) from exc
        raise HTTPException(status_code=404, detail="El perfil no existe o cambió de usuario") from exc
    except instaloader.exceptions.QueryReturnedForbiddenException as exc:
        state = classify_session_exception(exc)
        raise map_session_state_to_http_error(state) from exc
    except instaloader.exceptions.TooManyRequestsException as exc:
        raise HTTPException(
            status_code=503,
            detail="Instagram limitó temporalmente la sesión. Espera unos minutos e intenta de nuevo."
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
