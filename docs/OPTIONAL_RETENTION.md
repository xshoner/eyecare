# Optional research-data retention decision

No retention period or automatic deletion is included in the minimum owner-only schema proposal. The user did not request a 180-day period.

If a future policy chooses 180 days, it would require a separately approved server-side deletion job, a documented start date, a withdrawal path, failure monitoring, and synthetic tests proving physical deletion. An `expires_at` field or RLS visibility cutoff alone would only hide rows; it would not erase them. The research team should choose the period and account-recovery/deletion behavior before collecting real personal observations.
