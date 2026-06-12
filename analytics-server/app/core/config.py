from pydantic import model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    app_name: str = "LoveACE Analytics"
    database_url: str = ""
    analytics_api_key: str = ""
    analytics_signing_secret: str = ""
    analytics_hash_salt: str = ""
    ip_hash_salt: str = ""
    log_level: str = "INFO"
    timestamp_skew_seconds: int = 300
    max_body_bytes: int = 64 * 1024
    max_events_per_request: int = 50
    nonce_ttl_seconds: int = 600
    rate_limit_per_minute: int = 120

    model_config = SettingsConfigDict(env_file=".env", env_file_encoding="utf-8", extra="ignore")

    @model_validator(mode="after")
    def validate_required_settings(self) -> "Settings":
        missing = [
            name
            for name in (
                "database_url",
                "analytics_api_key",
                "analytics_signing_secret",
                "ip_hash_salt",
            )
            if not getattr(self, name)
        ]
        if missing:
            raise ValueError(f"Missing required settings: {', '.join(missing)}")
        return self


settings = Settings()
