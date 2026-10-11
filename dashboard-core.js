/* Pure research-analysis helpers. No authentication state or personal data is persisted here. */
(function (root, factory) {
  const api = factory();
  if (typeof module === 'object' && module.exports) module.exports = api;
  else root.EyeCareAnalysis = api;
})(typeof window === 'undefined' ? globalThis : window, function () {
  const domains = [
    { id: 'screen', label: '화면 자극', weight: 26 },
    { id: 'environment', label: '주변 환경', weight: 22 },
    { id: 'posture', label: '자세·움직임', weight: 8 },
    { id: 'usage', label: '사용 습관', weight: 18 },
    { id: 'eyes', label: '눈 반응', weight: 26 }
  ];
  const isSynthetic = row => String(row.algorithm_version || '').startsWith('synthetic-test-');
  const isScore = value => typeof value === 'number' && Number.isFinite(value) && value >= 0 && value <= 100;
  const isNrs = value => Number.isInteger(value) && value >= 0 && value <= 10;
  const finite = (value, low, high) => typeof value === 'number' && Number.isFinite(value) &&
    value >= low && value <= high;
  const scoreWeights = version => version === 'efs-hypothesis-0.7' ? [26, 22, 8, 18, 26]
    : version === 'efs-hypothesis-0.5' ? [20, 10, 15, 20, 35] : null;

  // Mirrors the app's >=30% available-weight gate. Older rows with no quality evidence
  // retain their stored scores in the table, but cannot support a trend comparison.
  function comparableScore(row) {
    const weights = scoreWeights(row.algorithm_version);
    if (!isScore(row.score) || !weights) return false;
    const available = domains.reduce((sum, domain, i) => {
      const quality = row[`${domain.id}_quality`];
      return sum + (finite(quality, 0, 1) ? weights[i] * quality : 0);
    }, 0);
    return Math.round(available) >= 30;
  }

  function distanceSummary(rows) {
    const windows = rows.filter(row => finite(row.distance_window_minutes, 0, 120) &&
      finite(row.distance_valid_minutes, 0, row.distance_window_minutes));
    const observedMinutes = windows.reduce((sum, row) => sum + row.distance_valid_minutes, 0);
    const windowMinutes = windows.reduce((sum, row) => sum + row.distance_window_minutes, 0);
    const measured = windows.filter(row => finite(row.distance_estimate_cm, 15, 80) &&
      finite(row.distance_estimate_quality, 0, 1) && row.distance_estimate_quality > 0 &&
      row.distance_valid_minutes > 0 &&
      typeof row.distance_calibrated === 'boolean');
    const totalWeight = measured.reduce((sum, row) => sum + row.distance_valid_minutes, 0);
    let typicalCm = null;
    if (totalWeight > 0) {
      let accumulated = 0;
      typicalCm = [...measured].sort((a, b) => a.distance_estimate_cm - b.distance_estimate_cm)
        .find(row => (accumulated += row.distance_valid_minutes) * 2 >= totalWeight).distance_estimate_cm;
    }
    return { typicalCm, measured, observedMinutes, windowMinutes,
      coverage: windowMinutes > 0 ? Math.min(1, observedMinutes / windowMinutes) : null,
      calibrated: measured.length ? measured.every(row => row.distance_calibrated) : null };
  }

  function partition(rows) {
    return {
      real: rows.filter(row => !isSynthetic(row)),
      synthetic: rows.filter(isSynthetic)
    };
  }

  function paired(rows) {
    return rows.filter(row => comparableScore(row) && isNrs(row.subjective_fatigue_nrs));
  }

  // A response is a separate immutable event. The latest server-received event
  // replaces the old inline value for display, without changing the observation.
  function attachResponses(rows, responses) {
    const latest = new Map();
    for (const answer of responses) {
      if (!isNrs(answer.subjective_fatigue_nrs) || !Number.isFinite(Date.parse(answer.received_at))) continue;
      const old = latest.get(answer.report_id);
      if (!old || answer.received_at > old.received_at ||
        (answer.received_at === old.received_at && answer.id > old.id)) latest.set(answer.report_id, answer);
    }
    return rows.map(row => {
      const answer = latest.get(row.report_id);
      return answer ? { ...row, subjective_fatigue_nrs: answer.subjective_fatigue_nrs,
        subjective_source: 'response', subjective_received_at: answer.received_at } :
        { ...row, subjective_source: isNrs(row.subjective_fatigue_nrs) ? 'inline' : null };
    });
  }

  function personalized(target, rows, responses) {
    if (!comparableScore(target)) return { score: null, samples: 0, offset: null };
    const targetAt = Date.parse(target.observed_at);
    const byReport = new Map(rows.map(row => [row.report_id, row]));
    const latestPrior = new Map();
    for (const answer of responses) {
      const at = Date.parse(answer.received_at);
      if (!isNrs(answer.subjective_fatigue_nrs) || !Number.isFinite(at) || at >= targetAt) continue;
      const old = latestPrior.get(answer.report_id);
      if (!old || answer.received_at > old.received_at ||
        (answer.received_at === old.received_at && answer.id > old.id)) latestPrior.set(answer.report_id, answer);
    }
    const offsets = [...latestPrior.values()].flatMap(answer => {
      const row = byReport.get(answer.report_id);
      if (!row || !comparableScore(row) || row.algorithm_version !== target.algorithm_version) return [];
      const measured = Date.parse(row.observed_at), answered = Date.parse(answer.answered_at);
      if (!(measured < targetAt && measured <= answered && answered - measured <= 86400000)) return [];
      return [answer.subjective_fatigue_nrs * 10 - row.score];
    }).sort((a, b) => a - b);
    if (offsets.length < 5) return { score: null, samples: offsets.length, offset: null };
    const mid = Math.floor(offsets.length / 2);
    const median = offsets.length % 2 ? offsets[mid] : (offsets[mid - 1] + offsets[mid]) / 2;
    const raw = median * offsets.length / (offsets.length + 10);
    const offset = Math.max(-12, Math.min(12, Math.sign(raw) * Math.floor(Math.abs(raw) + .5)));
    return { score: Math.max(0, Math.min(100, target.score + offset)), samples: offsets.length, offset };
  }

  function contributions(row) {
    const historical = row.algorithm_version !== 'efs-hypothesis-0.7';
    const old = [20, 10, 15, 20, 35];
    const observed = domains.map((domain, index) => {
      const weight = historical ? old[index] : domain.weight;
      const score = row[`${domain.id}_score`];
      const quality = row[`${domain.id}_quality`];
      const valid = isScore(score) && typeof quality === 'number' && quality > 0 && quality <= 1;
      return { ...domain, weight, score: valid ? score : null, quality: valid ? quality : null,
        effectiveWeight: valid ? weight * quality : 0 };
    });
    const total = observed.reduce((sum, item) => sum + item.effectiveWeight, 0);
    return observed.map(item => ({ ...item,
      reconstructedPoints: item.score === null || total === 0 ? null : item.effectiveWeight / total * item.score
    }));
  }

  function peak(rows) {
    return rows.filter(comparableScore).reduce((best, row) =>
      !best || row.score > best.score ? row : best, null);
  }

  function grantState(previous, grant, now = Date.now()) {
    if (grant && !grant.revoked_at && Date.parse(grant.expires_at) > now) return 'linked';
    if (previous && Date.parse(previous.expires_at) <= now) return 'expired';
    if (previous) return 'revoked';
    return 'waiting';
  }

  return { domains, isSynthetic, partition, paired, attachResponses, personalized, contributions, peak, grantState,
    isScore, comparableScore, distanceSummary };
});
