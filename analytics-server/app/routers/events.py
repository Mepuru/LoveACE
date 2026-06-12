import json
from datetime import datetime, timedelta, timezone

from fastapi import APIRouter, Depends, HTTPException, Request, status
from sqlalchemy import delete
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.config import settings
from app.core.security import authenticate_request, rate_limiter
from app.db.models import AnalyticsEvent, AnalyticsNonce
from app.db.session import get_session
from app.schemas.events import EventsIn, EventsOut

router = APIRouter(prefix="/v1", tags=["events"])


@router.post("/events", response_model=EventsOut)
async def ingest_events(request: Request, session: AsyncSession = Depends(get_session)):
    auth = await authenticate_request(request)
    nonce = request.headers["X-LoveACE-Nonce"]

    try:
        payload = EventsIn.model_validate_json(auth.body)
    except Exception as exc:
        raise HTTPException(status.HTTP_400_BAD_REQUEST, "Invalid payload") from exc

    if not payload.events:
        return EventsOut(ok=True, accepted=0)
    if len(payload.events) > settings.max_events_per_request:
        raise HTTPException(status.HTTP_400_BAD_REQUEST, "Too many events")

    if not rate_limiter.allow(f"client:{payload.client_id}", settings.rate_limit_per_minute):
        raise HTTPException(status.HTTP_429_TOO_MANY_REQUESTS, "Rate limited")
    if payload.student_hash and not rate_limiter.allow(
        f"student:{payload.student_hash}", settings.rate_limit_per_minute
    ):
        raise HTTPException(status.HTTP_429_TOO_MANY_REQUESTS, "Rate limited")

    now = datetime.now(timezone.utc)
    await session.execute(delete(AnalyticsNonce).where(AnalyticsNonce.expires_at < now))
    session.add(
        AnalyticsNonce(
            nonce=nonce,
            expires_at=now + timedelta(seconds=settings.nonce_ttl_seconds),
        )
    )
    try:
        await session.flush()
    except IntegrityError as exc:
        await session.rollback()
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "Replay detected") from exc

    for event in payload.events:
        session.add(
            AnalyticsEvent(
                client_id=payload.client_id,
                platform=payload.platform,
                app_version=payload.app_version,
                build=payload.build,
                os_version=payload.os_version,
                device_model=payload.device_model,
                grade_prefix=payload.grade_prefix,
                student_hash=payload.student_hash,
                event_name=event.name,
                event_time=event.time,
                properties=json.loads(json.dumps(event.properties)),
                ip_hash=auth.ip_hash,
                user_agent=auth.user_agent,
            )
        )

    await session.commit()
    return EventsOut(ok=True, accepted=len(payload.events))
