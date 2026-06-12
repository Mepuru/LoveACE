from datetime import datetime, timezone

from sqlalchemy import BigInteger, DateTime, Index, Integer, String, Text, UniqueConstraint
from sqlalchemy.dialects.postgresql import JSONB
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column


class Base(DeclarativeBase):
    pass


class AnalyticsEvent(Base):
    __tablename__ = "analytics_events"

    id: Mapped[int] = mapped_column(BigInteger, primary_key=True, autoincrement=True)
    client_id: Mapped[str] = mapped_column(Text, nullable=False)
    platform: Mapped[str] = mapped_column(String(32), nullable=False)
    app_version: Mapped[str] = mapped_column(String(64), nullable=False)
    build: Mapped[str | None] = mapped_column(String(64))
    os_version: Mapped[str | None] = mapped_column(String(128))
    device_model: Mapped[str | None] = mapped_column(String(128))
    grade_prefix: Mapped[str | None] = mapped_column(String(4))
    student_hash: Mapped[str | None] = mapped_column(String(64))
    event_name: Mapped[str] = mapped_column(String(64), nullable=False)
    event_time: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    received_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=lambda: datetime.now(timezone.utc), nullable=False
    )
    properties: Mapped[dict] = mapped_column(JSONB, nullable=False, default=dict)
    ip_hash: Mapped[str | None] = mapped_column(String(64))
    user_agent: Mapped[str | None] = mapped_column(Text)

    __table_args__ = (
        Index("idx_events_event_time", "event_time"),
        Index("idx_events_event_name_time", "event_name", "event_time"),
        Index("idx_events_platform_time", "platform", "event_time"),
        Index("idx_events_grade_prefix_time", "grade_prefix", "event_time"),
        Index("idx_events_student_hash_time", "student_hash", "event_time"),
    )


class AnalyticsNonce(Base):
    __tablename__ = "analytics_nonces"

    id: Mapped[int] = mapped_column(BigInteger, primary_key=True, autoincrement=True)
    nonce: Mapped[str] = mapped_column(String(128), nullable=False)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=lambda: datetime.now(timezone.utc), nullable=False
    )
    expires_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)

    __table_args__ = (
        UniqueConstraint("nonce", name="uq_analytics_nonce"),
        Index("idx_analytics_nonce_expires_at", "expires_at"),
    )


class AnalyticsRejectedRequest(Base):
    __tablename__ = "analytics_rejected_requests"

    id: Mapped[int] = mapped_column(BigInteger, primary_key=True, autoincrement=True)
    reason: Mapped[str] = mapped_column(String(64), nullable=False)
    ip_hash: Mapped[str | None] = mapped_column(String(64))
    user_agent: Mapped[str | None] = mapped_column(Text)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=lambda: datetime.now(timezone.utc), nullable=False
    )
    count: Mapped[int] = mapped_column(Integer, default=1, nullable=False)
