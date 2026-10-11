# EyeCare Research

This static site has a public [synthetic methodology simulator](index.html), a browser-only [algorithm editor](algorithm.html), and a separate [paired research dashboard](dashboard.html). The dashboard signs in anonymously, displays a QR containing its browser Auth UUID, and reads a phone owner's observations only while that owner has approved an active grant. No tokens or observations are embedded in the site build.

The dashboard shows a score timeline and peak, approximate quality-weighted domain contributions, same-report score/NRS pairs, and missing-data and algorithm-version summaries. API test rows marked `synthetic-test-` are identified separately. Untagged rows remain provenance-unverified candidates, not confirmed real measurements. The editor saves only local weight settings and does not alter stored observations or phone scoring. Scores are exploratory, not clinical advice.

Run `npm run check` and `npm test` to verify the JavaScript. The root can be deployed as a static site. The pinned QR generator is vendored at `vendor/qrcode.js`, so the page does not need a third-party runtime CDN.

[RESEARCH_PIPELINE.md](RESEARCH_PIPELINE.md) documents Auth, grants, consent, upload, and limitations. [ENGINE_0.7.md](ENGINE_0.7.md) documents the new user-selected weights and aggregate distance estimate. Historical SQL proposals in `docs/` are not migrations to rerun. The Android app source and APK are in the separate `D:\Neurolens2` project.
