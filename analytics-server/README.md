# LoveACE Analytics Server

LoveACE telemetry ingestion service powered by FastAPI, async SQLAlchemy, and PostgreSQL.

## Local development

```bash
cp .env.example .env
uv sync
docker compose up -d postgres
uv run uvicorn app.main:app --reload
```

Health check:

```bash
curl http://127.0.0.1:8000/healthz
```

## Docker deployment

```bash
cp .env.example .env
docker compose up -d --build
```

The service listens on `127.0.0.1:7788` on the host and should be exposed through a reverse proxy with HTTPS.

## Security

Clients must send:

- `Authorization: Bearer <ANALYTICS_API_KEY>`
- `X-LoveACE-Timestamp`
- `X-LoveACE-Nonce`
- `X-LoveACE-Signature`

Signature:

```text
HMAC_SHA256(ANALYTICS_SIGNING_SECRET, timestamp + "." + nonce + "." + sha256(raw_request_body))
```
