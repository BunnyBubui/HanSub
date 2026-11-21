# HanSub context translation backend

This optional server uses Gemini API to translate a final Mandarin speech segment,
with up to six previous finalized Chinese lines and user-defined character names.
Partial segments are handled by ML Kit locally for lower latency.

Available modes:
- cloud_fast → gemini-3.5-flash-lite (target: lower cost / latency)
- cloud_quality → gemini-3.8-flash (target: stronger context; not yet benchmarked)

The app must point to an HTTPS URL ending in `/translate`.
Set a custom bearer token in Android settings, not the Gemini API key.
Backend must have `GEMINI_API_KEY` and `HANSUB_CLIENT_TOKEN` environment
variables with a long random string. Do not commit secrets.

## Deploy Cloud Run (example)

```bash
cd backend
gcloud run deploy hansub-translate --source . --region asia-southeast1 \
  --allow-unauthenticated \
  --set-secrets=GEMINI_API_KEY=hansub-gemini-key:latest,HANSUB_CLIENT_TOKEN=hansub-client-token:latest
```

Cloud Run authentication is public at the HTTPS ingress level, but this app
checks a bearer token on each translation call. Add Cloud Armor, quotas,
logging scrubbing, per-user identity and distributed rate limiting before
multi-user production use. Do not log request payloads or the dialogue.
The minimal in-memory rate limiter is per-container replica only.

**No backend or API key is provisioned by this repository.** Cloud modes
fall back to on-device ML Kit when not configured or a call fails.

Gemini API billing and data retention terms are separate from offline ML Kit.
Follow service policies when translating copyrighted video content.
