# Research data pipeline — 0.7.0 alpha

The public home page is a synthetic-data methodology simulator. The separate [private dashboard](dashboard.html) signs its browser into Supabase anonymously and reads only observations authorized by a time-limited phone-to-browser grant. It has no public observation feed.

## Android export

- Research collection is optional and requires explicit consent. The Android app signs in anonymously, protects its Auth session with Android Keystore, and uploads aggregate fields for completed reports through its retrying outbox. Export is also available for a local copy.
- The pending outbox uses stable report IDs, retries after offline/transient failures, and handles a duplicate insert by checking that the same owner already has the report. Observation rows are insert-only.
- The optional subjective fatigue NRS can accompany the immutable observation when supplied before its first upload. Missing values stay null; they are never treated as zero.
- Withdrawal stops future uploads and clears pending local reports. It does not delete existing server records. The user must explicitly consent again before resuming.
- Uninstalling the app loses the anonymous owner identity and its ability to recover those rows or revoke browser access. Reinstalling creates a new owner.

## Browser pairing

1. The dashboard signs in anonymously and presents its Auth UUID in a QR code. The QR contains only that UUID, never a token, report, or observation.
2. The phone scans or accepts the UUID and asks for explicit approval. It writes a single owner-controlled grant, valid for at most 30 days. Replacing the browser or revoking the grant ends the earlier browser's read permission.
3. The browser queries its own valid grant, then the owner's observations under RLS. The browser has no observation write or delete permission. An offline revoke is queued on the phone and only takes effect on the server after a successful retry; the UI states this delay.

The database migration `20261010133126_create_owner_only_research_storage` created `research_consent` and `research_observations`. A separate approved migration created `research_browser_grants`. RLS restricts writes to the owner, browser reads to an active grant, and unauthenticated `anon` access to none. The files under `docs/` are historical proposals, not deployment instructions.

## Data and interpretation

The observation schema accepts scores, five domain scores and quality values, confidence, NRS, a short algorithm version, estimates/missing codes, and bounded aggregate measurements. It excludes camera/screen originals, screen text, application names, full gaze traces, account names, and free-form notes.

The dashboard plots observed score against time, highlights the peak, shows paired score/NRS records only when both exist in the same report, and reconstructs domain contributions using nominal weights and stored quality. Exact adjustment components and raw feature counts are absent from the research schema, so reconstructed contributions are labeled as approximate. Missing values are shown as missing. Records tagged `synthetic-test-` stay out of candidate charts and counts. Untagged rows have not been independently proven to originate from real phone measurements. These exploratory charts are not clinical validation.

The [algorithm editor](algorithm.html) is a browser-only what-if calculator over synthetic examples, also available with paired candidate rows in the dashboard. It saves versioned weight settings in browser storage and can export their JSON. It does not update the database or phone algorithm. The 0.7 app uses user-selected `26/22/8/18/26` weights and local distance exposure; earlier 0.5 rows retain their historical `20/10/15/20/35` reconstruction. The web cannot reproduce 0.7 distance from the current research schema. Scarce and provenance-unverified NRS labels were not used to fit a new score. See [ENGINE_0.7.md](ENGINE_0.7.md) for the formula, camera assumptions, and validation limits.

## Validation

The project includes `npm run check` and `npm test` for the dashboard code. The Android project builds unit, instrumentation, debug APK, and lint targets separately. A synthetic-only API exercise checked owner/grant isolation, browser read, browser write denial, maximum grant duration, and revoke. It intentionally retains a tagged synthetic observation. No personal health record was used in that test.

The Android source is in the separate `D:\Neurolens2` project. No service-role secret belongs in either public source or the APK.
