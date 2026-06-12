# LoveACE Analytics Worker

Privacy-friendly analytics endpoint for LoveACE clients, deployed on Cloudflare Workers with D1.

## Endpoints

- `GET /healthz`
- `POST /v1/events`

Requests to `/v1/events` must include:

- `Authorization: Bearer <ANALYTICS_API_KEY>`
- `X-LoveACE-Timestamp`
- `X-LoveACE-Nonce`
- `X-LoveACE-Signature`

Signature payload:

```text
HMAC_SHA256(ANALYTICS_SIGNING_SECRET, timestamp + "." + nonce + "." + sha256(raw_body))
```

The service stores `grade_prefix` and salted `student_hash`, never the full plaintext student id.
