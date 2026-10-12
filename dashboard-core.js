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
  // App 0.14+ deduction model: score = today's accumulated deduction (100 − eye condition).
  const DEDUCTION_VERSIONS = ['efs-deduction-0.14', 'efs-deduction-0.15'];
  const isDeduction = version => DEDUCTION_VERSIONS.includes(version);
  // The app's five display bands on the condition scale (higher = better).
  const BANDS = [[80, '매우 양호'], [60, '양호'], [40, '보통'], [20, '주의'], [0, '위험']];
  function condition(row) {
    if (!isScore(row.score)) return null;
    const value = 100 - row.score;
    return { value, band: BANDS.find(([low]) => value >= low)[1] };
  }
  const scoreWeights = version => ['efs-hypothesis-0.7', 'efs-exposure-0.10', 'efs-exposure-0.11'].includes(version) ? [26, 22, 8, 18, 26]
    : version === 'efs-hypothesis-0.5' ? [20, 10, 15, 20, 35] : null;

  // Mirrors the app's >=30% available-weight gate. Older rows with no quality evidence
  // retain their stored scores in the table, but cannot support a trend comparison.
  function comparableScore(row) {
    const weights = scoreWeights(row.algorithm_version);
    if (isDeduction(row.algorithm_version)) {
      // The phone marks a deduction report adequate only when at least half of its window
      // measured two or more of light, distance and screen stimulation.
      return isScore(row.score) && row.confidence === 'adequate';
    }
    if (!isScore(row.score) || !weights) return false;
    if (row.algorithm_version === 'efs-exposure-0.10') {
      // The phone has already gated its complete score. Do not infer one from partial
      // domain rows; the daily time integral and interaction are not sent to the server.
      return ['partial', 'adequate'].includes(row.confidence) &&
        isScore(row.screen_score) && isScore(row.usage_score) &&
        (isScore(row.environment_score) || isScore(row.eyes_score));
    }
    if (row.algorithm_version === 'efs-exposure-0.11') {
      // A partial number remains visible in the table. Correlations use only
      // adequately observed rows with at least 90% of nominal domains present.
      const present = domains.reduce((sum, domain, i) => sum +
        (isScore(row[`${domain.id}_score`]) && finite(row[`${domain.id}_quality`], 0, 1) &&
          row[`${domain.id}_quality`] > 0 ? weights[i] : 0), 0);
      return row.confidence === 'adequate' && present >= 90;
    }
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

  // Forward-only held-out comparison on the latest algorithm version. A report's own
  // response is never available to its prediction, and later corrections do not rewrite outcomes.
  function validation(rows, responses) {
    const chronological = [...rows].filter(row => Number.isFinite(Date.parse(row.observed_at)))
      .sort((a, b) => Date.parse(a.observed_at) - Date.parse(b.observed_at) ||
        String(a.report_id).localeCompare(String(b.report_id)));
    const version = chronological.at(-1)?.algorithm_version || null;
    const candidates = chronological.filter(row => row.algorithm_version === version && comparableScore(row));
    const first = new Map();
    for (const answer of responses) {
      if (!isNrs(answer.subjective_fatigue_nrs) || !Number.isFinite(Date.parse(answer.received_at))) continue;
      const old = first.get(answer.report_id);
      if (!old || answer.received_at < old.received_at ||
        (answer.received_at === old.received_at && answer.id < old.id)) first.set(answer.report_id, answer);
    }
    let eligibleLabels = 0, evaluated = 0, raw = 0, personal = 0;
    let firstEvaluatedAt = null, lastEvaluatedAt = null;
    for (const row of candidates) {
      const answer = first.get(row.report_id);
      if (!answer) continue;
      const reportAt = Date.parse(row.observed_at), answeredAt = Date.parse(answer.answered_at);
      if (!Number.isFinite(answeredAt) || answeredAt < reportAt || answeredAt - reportAt > 86400000) continue;
      eligibleLabels++;
      const predicted = personalized(row, candidates, responses).score;
      if (predicted === null) continue;
      raw += Math.abs(row.score - answer.subjective_fatigue_nrs * 10);
      personal += Math.abs(predicted - answer.subjective_fatigue_nrs * 10);
      evaluated++;
      if (!firstEvaluatedAt) firstEvaluatedAt = row.observed_at;
      lastEvaluatedAt = row.observed_at;
    }
    return { version, sameVersionReports: candidates.length, eligibleLabels, evaluated,
      rawMae: evaluated ? raw / evaluated : null, personalMae: evaluated ? personal / evaluated : null,
      firstEvaluatedAt, lastEvaluatedAt, sufficientEvidence: evaluated >= 5 };
  }

  function contributions(row) {
    const exposure = ['efs-exposure-0.10', 'efs-exposure-0.11'].includes(row.algorithm_version) ||
      isDeduction(row.algorithm_version);
    const historical = row.algorithm_version === 'efs-hypothesis-0.5';
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
      reconstructedPoints: item.score === null || total === 0 ? null : exposure
        ? item.weight * item.score / 100 : item.effectiveWeight / total * item.score,
      kind: exposure ? 'rate' : 'historical_mean'
    }));
  }

  function peak(rows) {
    return rows.filter(comparableScore).reduce((best, row) =>
      !best || row.score > best.score ? row : best, null);
  }

  function newestVersionRows(rows) {
    const dated = rows.filter(row => Number.isFinite(Date.parse(row.observed_at)))
      .sort((a, b) => Date.parse(a.observed_at) - Date.parse(b.observed_at));
    const version = dated.at(-1)?.algorithm_version;
    return version ? dated.filter(row => row.algorithm_version === version) : [];
  }

  // CSV for offline validation. The stored `score` stays the burden score (higher = more burden);
  // `condition_score` is the phone's 0.12 display value. Empty cells are unobserved, never zero.
  const csvCell = value => {
    const text = value === null || value === undefined ? ''
      : typeof value === 'number' ? (Number.isFinite(value) ? String(value) : '') : String(value);
    return /[",\r\n]/.test(text) ? `"${text.replace(/"/g, '""')}"` : text;
  };
  const csvText = (columns, rows) => [columns, ...rows.map(row => columns.map(column => row[column]))]
    .map(line => line.map(csvCell).join(',')).join('\r\n') + '\r\n';
  const RESPONSE_COLUMNS = ['id', 'report_id', 'subjective_fatigue_nrs', 'answered_at', 'received_at', 'previous_response_id'];

  function observationsCsv(rows, responses) {
    const byReport = new Map();
    for (const answer of responses) {
      if (!isNrs(answer.subjective_fatigue_nrs)) continue;
      const list = byReport.get(answer.report_id) || [];
      list.push(answer); byReport.set(answer.report_id, list);
    }
    const stored = [...new Set(rows.flatMap(row => Object.keys(row)))]
      .filter(key => !['subjective_source', 'subjective_response_id'].includes(key));
    const columns = [...stored, 'condition_score', 'comparable', 'synthetic',
      'nrs_response_count', 'nrs_first', 'nrs_first_answered_at', 'nrs_latest', 'nrs_latest_received_at'];
    const ordered = [...rows].sort((a, b) => String(a.observed_at).localeCompare(String(b.observed_at)));
    return csvText(columns, ordered.map(row => {
      const answers = byReport.get(row.report_id) || [];
      const first = [...answers].sort((a, b) => String(a.answered_at).localeCompare(String(b.answered_at)) || String(a.id).localeCompare(String(b.id)))[0];
      const latest = [...answers].sort((a, b) => String(b.received_at).localeCompare(String(a.received_at)) || String(b.id).localeCompare(String(a.id)))[0];
      return { ...row, condition_score: isScore(row.score) ? 100 - row.score : null,
        comparable: comparableScore(row), synthetic: isSynthetic(row), nrs_response_count: answers.length,
        nrs_first: first?.subjective_fatigue_nrs, nrs_first_answered_at: first?.answered_at,
        nrs_latest: latest?.subjective_fatigue_nrs, nrs_latest_received_at: latest?.received_at };
    }));
  }

  function responsesCsv(responses) {
    return csvText(RESPONSE_COLUMNS, [...responses].sort((a, b) =>
      String(a.received_at).localeCompare(String(b.received_at)) || String(a.id).localeCompare(String(b.id))));
  }

  function grantState(previous, grant, now = Date.now()) {
    if (grant && !grant.revoked_at && Date.parse(grant.expires_at) > now) return 'linked';
    if (previous && Date.parse(previous.expires_at) <= now) return 'expired';
    if (previous) return 'revoked';
    return 'waiting';
  }

  return { domains, isSynthetic, isDeduction, condition, DEDUCTION_VERSIONS, partition, paired, attachResponses, personalized, validation,
    contributions, peak, newestVersionRows, grantState,
    isScore, comparableScore, distanceSummary, observationsCsv, responsesCsv };
});
