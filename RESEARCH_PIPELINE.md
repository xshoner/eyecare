# Research data pipeline proposal — approval required before activation

The public site is currently a synthetic-data methodology demo. It does not read or upload real Supabase records. The Android 0.5.0 alpha likewise does not send personal measurements to this project. The supplied publishable key identifies a client application; by itself it neither authenticates a person nor authorizes private row access. No service-role key or password should enter source control, APKs, logs, or chat.

## Proposed access model

1. Enable Supabase anonymous Auth for a consenting Android installation. Its authenticated `auth.uid()` owns its rows. Store its session in app-private storage. Because anonymous identity is lost on uninstall/sign-out, explain that limitation before consent or provide a separately approved account-linking flow.
2. Review `docs/PROPOSED_20261010_research_schema.sql` and apply it manually only after approval. The proposal is outside the migrations directory to prevent automatic application. The proposal revokes `anon` table access, enables RLS, and limits authenticated insert/read/delete to the owner's rows while active consent exists. There is no public read or unauthenticated write. Neither the migration nor this site adds a researcher-wide read policy.
3. For a researcher dashboard, separately approve an identified researcher login, a narrowly scoped read authorization mechanism, and RLS tests. Only then add authenticated site access. No real records should appear on a public route or in static assets.
4. Add an approved scheduled purge for expired rows (proposed retention: 180 days). The proposed `expires_at` column alone does not physically delete data. On consent withdrawal, stop future uploads, delete the owner's rows, and remove the on-device queued copy once server deletion is confirmed. Handle unavailable network with a bounded retry queue and stable report IDs for idempotent inserts.

## Minimum record and data dictionary

The SQL schema intentionally uses named numeric columns rather than arbitrary JSON payloads so that an Android client cannot place image bytes, screen text, application names, account names, or free-form notes into a research field. Required fields are a random observation UUID, authenticated owner UUID, stable report UUID for deduplication, random device pseudonym UUID, observation time, consent version and algorithm version. The optional numeric fields are 0–100 score and five domain scores, five 0–1 domain quality values, screen-change fraction, ambient lux, continuous and daily use minutes, blink rate, eye-open mean, continuous dark/bright exposure minutes, **calibrated** distance only when genuinely available, and a 0–10 optional subjective fatigue response. `null` means not measured; it is not zero.

`split` is determined by the owner ID so one participant's records cannot appear in both calibration and held-out validation sets. That is a data-management partition, not model performance evidence. Timestamps and pseudonyms are still personal data and require consent and restricted access. Do not upload source images, videos, full gaze traces, app identities, screen text, or notification content.

## Android workflow to activate after approval

- Show a separate, optional research-upload consent screen with schema/version and exact fields; on-device scoring works without it. Start Auth and remote insertion only after consent.
- Insert each sanitized report once using its immutable report ID. A local outbox should retain failed requests, back off on transient errors, and show pending/sent/failed state. If offline, continue on-device operation.
- Upload a newly supplied subjective fatigue response as an approved update operation or a separate response row; avoid silently changing immutable observations. The current proposed schema grants no UPDATE, so a response can only be included before first insert until that choice is resolved.
- Implement and test withdrawal, server delete, local queue removal, token expiry, duplicate response, clock drift, and authorization failures with synthetic records before live use.

## Concrete approvals and verification still needed

Approval is needed to enable anonymous Auth, apply the RLS/grant migration, choose a researcher identity and access policy, and configure a retention purge. Decide whether responses should be inserted with a report or updated later. Run owner/other-user/anonymous/researcher read/write/delete tests against a non-production project first, then the named project with synthetic data. Check Vercel's Git connection and production deployment state before describing the public site as updated. No automatic model retraining or production rollout follows from record collection.

Relevant official guidance: [Supabase anonymous Auth](https://supabase.com/docs/guides/auth/auth-anonymous), [RLS](https://supabase.com/docs/guides/database/postgres/row-level-security), [securing the Data API](https://supabase.com/docs/guides/api/securing-your-api), [Vercel Git deployment](https://vercel.com/docs/git/vercel-for-github).
