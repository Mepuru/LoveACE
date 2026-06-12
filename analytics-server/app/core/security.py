import hashlib
import hmac
import time
from collections import defaultdict, deque
from dataclasses import dataclass

from fastapi import HTTPException, Request, status

from app.core.config import settings


@dataclass
class AuthenticatedRequest:
    body: bytes
    ip_hash: str | None
    user_agent: str | None


class MinuteRateLimiter:
    def __init__(self) -> None:
        self._hits: dict[str, deque[float]] = defaultdict(deque)

    def allow(self, key: str, limit: int) -> bool:
        now = time.time()
        window_start = now - 60
        hits = self._hits[key]
        while hits and hits[0] < window_start:
            hits.popleft()
        if len(hits) >= limit:
            return False
        hits.append(now)
        return True


rate_limiter = MinuteRateLimiter()


def hash_ip(ip: str | None) -> str | None:
    if not ip:
        return None
    return hashlib.sha256(f"{settings.ip_hash_salt}:{ip}".encode()).hexdigest()


def _header(request: Request, name: str) -> str:
    value = request.headers.get(name)
    if not value:
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, f"Missing {name}")
    return value


async def authenticate_request(request: Request) -> AuthenticatedRequest:
    body = await request.body()
    if len(body) > settings.max_body_bytes:
        raise HTTPException(status.HTTP_413_REQUEST_ENTITY_TOO_LARGE, "Request body too large")

    authorization = _header(request, "Authorization")
    expected_authorization = f"Bearer {settings.analytics_api_key}"
    if not hmac.compare_digest(authorization, expected_authorization):
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "Invalid API key")

    timestamp_raw = _header(request, "X-LoveACE-Timestamp")
    nonce = _header(request, "X-LoveACE-Nonce")
    signature = _header(request, "X-LoveACE-Signature")

    try:
        timestamp = int(timestamp_raw)
    except ValueError as exc:
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "Invalid timestamp") from exc
    if abs(int(time.time()) - timestamp) > settings.timestamp_skew_seconds:
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "Expired timestamp")

    body_hash = hashlib.sha256(body).hexdigest()
    message = f"{timestamp_raw}.{nonce}.{body_hash}".encode()
    expected = hmac.new(
        settings.analytics_signing_secret.encode(), message, hashlib.sha256
    ).hexdigest()
    if not hmac.compare_digest(signature, expected):
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "Invalid signature")

    client_ip = request.client.host if request.client else None
    ip_hash = hash_ip(client_ip)
    rate_key = ip_hash or "unknown"
    if not rate_limiter.allow(f"ip:{rate_key}", settings.rate_limit_per_minute):
        raise HTTPException(status.HTTP_429_TOO_MANY_REQUESTS, "Rate limited")

    return AuthenticatedRequest(
        body=body,
        ip_hash=ip_hash,
        user_agent=request.headers.get("User-Agent"),
    )
