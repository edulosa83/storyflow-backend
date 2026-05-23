import base64
import os
import re
from typing import List, Literal, Optional
from urllib.parse import urlparse

import instaloader
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
_SESSION_B64_TEMP_PATH = "/tmp/instaloader-session"


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


def build_loader() -> instaloader.Instaloader:
    loader = instaloader.Instaloader(
        sleep=False,
        quiet=True,
        download_pictures=False,
        download_videos=False,
        save_metadata=False,
        compress_json=False,
    )

    username = os.getenv("IG_USERNAME", "").strip()
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
        profile = instaloader.Profile.from_username(loader.context, normalized)
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
            normalizedInput=normalized,
            source="instaloader",
            items=items,
        )

    except instaloader.exceptions.ProfileNotExistsException as exc:
        raise HTTPException(status_code=404, detail="El perfil no existe") from exc
    except instaloader.exceptions.LoginRequiredException as exc:
        raise HTTPException(status_code=500, detail="La sesión de Instagram no es válida") from exc
    except instaloader.exceptions.ConnectionException as exc:
        raise HTTPException(status_code=502, detail=f"Error de conexión con Instagram: {exc}") from exc
    except Exception as exc:  # noqa: BLE001
        raise HTTPException(status_code=500, detail=f"Error inesperado: {exc}") from exc
