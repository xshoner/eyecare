# EyeCare Research

A static, public methodology and **synthetic-data** simulator for EyeCare EFS hypothesis 0.5. Open `index.html` locally or deploy the repository's root as a static site. There are no remote data calls, credentials, analytics, or real-user records in this version.

- `index.html`, `styles.css`, `app.js`: responsive research dashboard, nominal 100-point weights, scenario demonstration, and highlighted synthetic screen-change graph.
- `RESEARCH_PIPELINE.md`: proposed consent, authentication, retention, validation and Android upload contract.
- `docs/PROPOSED_20261010_research_schema.sql`: unexecuted migration proposal requiring security approval and RLS tests.

The Android source and algorithm details are in the separate `D:\Neurolens2` project (`ALGORITHM_0.5.md`). This website has not been wired to Supabase because Auth, RLS, researcher read rights, and deletion policy require explicit approval. Public GitHub/Vercel must never host real personal observations.
