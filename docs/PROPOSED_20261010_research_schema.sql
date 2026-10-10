-- PROPOSAL ONLY. Do not apply until Auth, owner-only RLS, consent and
-- account recovery/withdrawal behavior have been reviewed and approved.
-- No retention interval or automatic deletion is included in this proposal.
-- No service_role or other secret belongs in this repository or an Android APK.

create table if not exists public.research_consent (
  owner_id uuid primary key references auth.users(id) on delete cascade,
  consent_version text not null check (length(consent_version) between 1 and 40),
  granted_at timestamptz not null default now(),
  withdrawn_at timestamptz
);
alter table public.research_consent enable row level security;
revoke all on public.research_consent from anon, authenticated;
grant select on public.research_consent to authenticated;
grant insert (owner_id, consent_version) on public.research_consent to authenticated;
grant update (consent_version, withdrawn_at) on public.research_consent to authenticated;
create policy consent_owner_read on public.research_consent for select to authenticated
  using (owner_id = (select auth.uid()));
create policy consent_owner_insert on public.research_consent for insert to authenticated
  with check (owner_id = (select auth.uid()));
create policy consent_owner_update on public.research_consent for update to authenticated
  using (owner_id = (select auth.uid())) with check (owner_id = (select auth.uid()));
create table if not exists public.research_observations (
  id uuid primary key,
  owner_id uuid not null references auth.users(id) on delete cascade,
  report_id uuid not null,
  device_pseudonym uuid not null,
  observed_at timestamptz not null,
  received_at timestamptz not null default now(),
  consent_version text not null check (length(consent_version) between 1 and 40),
  algorithm_version text not null check (algorithm_version ~ '^[a-z0-9._-]{1,50}$'),
  score smallint check (score between 0 and 100),
  confidence text not null check (confidence in ('partial', 'adequate', 'unavailable')),
  screen_score smallint check (screen_score between 0 and 100),
  environment_score smallint check (environment_score between 0 and 100),
  posture_score smallint check (posture_score between 0 and 100),
  usage_score smallint check (usage_score between 0 and 100),
  eyes_score smallint check (eyes_score between 0 and 100),
  screen_quality real check (screen_quality between 0 and 1),
  environment_quality real check (environment_quality between 0 and 1),
  posture_quality real check (posture_quality between 0 and 1),
  usage_quality real check (usage_quality between 0 and 1),
  eyes_quality real check (eyes_quality between 0 and 1),
  screen_change real check (screen_change between 0 and 1),
  ambient_lux real check (ambient_lux between 0 and 200000),
  continuous_minutes real check (continuous_minutes between 0 and 1440),
  today_minutes real check (today_minutes between 0 and 1440),
  blink_per_min real check (blink_per_min between 0 and 120),
  eye_open_mean real check (eye_open_mean between 0 and 1),
  dark_stimulus_minutes real check (dark_stimulus_minutes between 0 and 10),
  calibrated_distance_cm real check (calibrated_distance_cm between 1 and 200),
  subjective_fatigue_nrs smallint check (subjective_fatigue_nrs between 0 and 10),
  estimated_fields text[] not null default '{}'::text[]
    check (estimated_fields <@ array['screen_change','ambient_lux','continuous_minutes',
      'today_minutes','blink_per_min','eye_open_mean','dark_stimulus_minutes',
      'calibrated_distance_cm']::text[]),
  screen_missing_code text check (screen_missing_code in ('no_capture','permission_missing','low_quality','unknown')),
  environment_missing_code text check (environment_missing_code in ('sensor_absent','permission_missing','low_quality','unknown')),
  posture_missing_code text check (posture_missing_code in ('sensor_absent','permission_missing','low_quality','unknown')),
  usage_missing_code text check (usage_missing_code in ('permission_missing','no_events','low_quality','unknown')),
  eyes_missing_code text check (eyes_missing_code in ('camera_permission_denied','camera_unavailable','low_quality','unknown')),
  split text generated always as
    (case when get_byte(decode(md5(owner_id::text), 'hex'), 0) % 5 = 0
      then 'validation' else 'calibration' end) stored,
  unique (owner_id, report_id),
  check (observed_at <= received_at + interval '1 day')
);
create index if not exists research_observations_owner_time
  on public.research_observations (owner_id, observed_at desc);
alter table public.research_observations enable row level security;
revoke all on public.research_observations from anon, authenticated;
grant select, delete on public.research_observations to authenticated;
-- Server receipt time and the generated split cannot be supplied by a client.
grant insert (id, owner_id, report_id, device_pseudonym, observed_at,
  consent_version, algorithm_version, score, confidence, screen_score,
  environment_score, posture_score, usage_score, eyes_score, screen_quality,
  environment_quality, posture_quality, usage_quality, eyes_quality,
  screen_change, ambient_lux, continuous_minutes, today_minutes, blink_per_min,
  eye_open_mean, dark_stimulus_minutes, calibrated_distance_cm,
  subjective_fatigue_nrs, estimated_fields, screen_missing_code,
  environment_missing_code, posture_missing_code, usage_missing_code,
  eyes_missing_code) on public.research_observations to authenticated;
create policy observation_owner_read on public.research_observations for select to authenticated
  using (owner_id = (select auth.uid()));
create policy observation_owner_insert on public.research_observations for insert to authenticated
  with check (owner_id = (select auth.uid()) and
    exists (select 1 from public.research_consent c
      where c.owner_id = research_observations.owner_id
      and c.withdrawn_at is null
      and c.consent_version = research_observations.consent_version));
create policy observation_owner_delete on public.research_observations for delete to authenticated
  using (owner_id = (select auth.uid()));

-- A researcher-wide read policy is intentionally omitted. It needs an approved
-- identity, dedicated authorization tests, and an access audit. No public read.
-- Retention and physical deletion, if desired, require a separate decision.
