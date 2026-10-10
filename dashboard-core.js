/* Pure research-analysis helpers. No authentication state or personal data is persisted here. */
(function (root, factory) {
  const api = factory();
  if (typeof module === 'object' && module.exports) module.exports = api;
  else root.EyeCareAnalysis = api;
})(typeof window === 'undefined' ? globalThis : window, function () {
  const domains = [
    { id: 'screen', label: '화면 자극', weight: 20 },
    { id: 'environment', label: '주변 환경', weight: 10 },
    { id: 'posture', label: '자세·움직임', weight: 15 },
    { id: 'usage', label: '사용 습관', weight: 20 },
    { id: 'eyes', label: '눈 반응', weight: 35 }
  ];
  const isSynthetic = row => String(row.algorithm_version || '').startsWith('synthetic-test-');
  const isScore = value => typeof value === 'number' && Number.isFinite(value) && value >= 0 && value <= 100;
  const isNrs = value => Number.isInteger(value) && value >= 0 && value <= 10;

  function partition(rows) {
    return {
      real: rows.filter(row => !isSynthetic(row)),
      synthetic: rows.filter(isSynthetic)
    };
  }

  function paired(rows) {
    return rows.filter(row => isScore(row.score) && isNrs(row.subjective_fatigue_nrs));
  }

  function contributions(row) {
    const observed = domains.map(domain => {
      const score = row[`${domain.id}_score`];
      const quality = row[`${domain.id}_quality`];
      const valid = isScore(score) && typeof quality === 'number' && quality > 0 && quality <= 1;
      return { ...domain, score: valid ? score : null, quality: valid ? quality : null,
        effectiveWeight: valid ? domain.weight * quality : 0 };
    });
    const total = observed.reduce((sum, item) => sum + item.effectiveWeight, 0);
    return observed.map(item => ({ ...item,
      reconstructedPoints: item.score === null || total === 0 ? null : item.effectiveWeight / total * item.score
    }));
  }

  function peak(rows) {
    return rows.filter(row => isScore(row.score)).reduce((best, row) =>
      !best || row.score > best.score ? row : best, null);
  }

  function grantState(previous, grant, now = Date.now()) {
    if (grant && !grant.revoked_at && Date.parse(grant.expires_at) > now) return 'linked';
    if (previous && Date.parse(previous.expires_at) <= now) return 'expired';
    if (previous) return 'revoked';
    return 'waiting';
  }

  return { domains, isSynthetic, partition, paired, contributions, peak, grantState, isScore };
});
