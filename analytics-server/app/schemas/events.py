from datetime import datetime
from typing import Any, Literal

from pydantic import BaseModel, Field, field_validator


ALLOWED_EVENTS = {
    "app_start",
    "login_success",
    "login_failed",
    "session_expired",
    "session_reconnect_success",
    "session_reconnect_failed",
    "screen_view",
    "feature_action",
    "ota_check",
    "ota_update_click",
}

ALLOWED_PROPERTY_KEYS = {
    "launch_source",
    "duration_ms",
    "reason",
    "feature",
    "screen",
    "action",
    "result",
    "current_version",
    "latest_version",
    "target_version",
}


class EventIn(BaseModel):
    name: str = Field(min_length=1, max_length=64)
    time: datetime
    properties: dict[str, Any] = Field(default_factory=dict)

    @field_validator("name")
    @classmethod
    def validate_name(cls, value: str) -> str:
        if value not in ALLOWED_EVENTS:
            raise ValueError("unknown event")
        return value

    @field_validator("properties")
    @classmethod
    def sanitize_properties(cls, value: dict[str, Any]) -> dict[str, Any]:
        clean: dict[str, Any] = {}
        for key, item in value.items():
            if key not in ALLOWED_PROPERTY_KEYS:
                continue
            if isinstance(item, str):
                clean[key] = item[:128]
            elif isinstance(item, bool | int | float) or item is None:
                clean[key] = item
        return clean


class EventsIn(BaseModel):
    client_id: str = Field(min_length=8, max_length=128)
    platform: Literal["android", "ios"]
    app_version: str = Field(min_length=1, max_length=64)
    build: str | None = Field(default=None, max_length=64)
    os_version: str | None = Field(default=None, max_length=128)
    device_model: str | None = Field(default=None, max_length=128)
    grade_prefix: str | None = Field(default=None, min_length=4, max_length=4)
    student_hash: str | None = Field(default=None, min_length=32, max_length=64)
    events: list[EventIn]

    @field_validator("grade_prefix")
    @classmethod
    def validate_grade_prefix(cls, value: str | None) -> str | None:
        if value is not None and not value.isdigit():
            raise ValueError("grade_prefix must be numeric")
        return value


class EventsOut(BaseModel):
    ok: bool
    accepted: int
