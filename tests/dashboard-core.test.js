const test = require('node:test');
const assert = require('node:assert/strict');
const A = require('../dashboard-core.js');

test('synthetic records never enter real charts or NRS pairs', () => {
  const rows = [
    { algorithm_version: 'synthetic-test-0.5.2', score: 90, subjective_fatigue_nrs: 10 },
    { algorithm_version: 'efs-hypothesis-0.5', score: 40, subjective_fatigue_nrs: null },
    { algorithm_version: 'efs-hypothesis-0.5', score: null, subjective_fatigue_nrs: 8 },
    { algorithm_version: 'efs-hypothesis-0.5', score: 55, subjective_fatigue_nrs: 7,
      screen_quality: 1, usage_quality: 1 }
  ];
  const { real, synthetic } = A.partition(rows);
  assert.equal(real.length, 3);
  assert.equal(synthetic.length, 1);
  assert.deepEqual(A.paired(real).map(row => row.score), [55]);
  assert.equal(A.peak(real).score, 55);
});

test('stored low-evidence scores remain present but cannot become trend or NRS points', () => {
  const weak = { algorithm_version: 'efs-hypothesis-0.7', score: 88,
    screen_quality: .5, environment_quality: .5, subjective_fatigue_nrs: 9 };
  const adequate = { algorithm_version: 'efs-hypothesis-0.7', score: 44,
    screen_quality: 1, environment_quality: .5, subjective_fatigue_nrs: 5 };
  assert.equal(A.comparableScore(weak), false); // 13 + 11 = 24 available weight
  assert.equal(A.comparableScore(adequate), true);
  assert.deepEqual(A.paired([weak, adequate]), [adequate]);
  assert.equal(A.peak([weak, adequate]), adequate);
  assert.equal(weak.score, 88);
});

test('distance uses valid minutes once and does not multiply composite quality by coverage', () => {
  const rows = [
    { distance_estimate_cm: 24, distance_estimate_quality: .4, distance_coverage: .5,
      distance_valid_minutes: 5, distance_window_minutes: 10, distance_calibrated: false },
    { distance_estimate_cm: 44, distance_estimate_quality: .7, distance_coverage: .1,
      distance_valid_minutes: 1, distance_window_minutes: 10, distance_calibrated: true },
    { distance_estimate_cm: null, distance_estimate_quality: null, distance_coverage: 0,
      distance_valid_minutes: 0, distance_window_minutes: 10, distance_calibrated: null },
    { distance_estimate_cm: 20, distance_estimate_quality: .9, distance_valid_minutes: null,
      distance_window_minutes: null, distance_calibrated: true },
    { distance_estimate_cm: 19, distance_estimate_quality: 0, distance_valid_minutes: 2,
      distance_window_minutes: 10, distance_calibrated: true }
  ];
  const result = A.distanceSummary(rows);
  assert.equal(result.typicalCm, 24);
  assert.equal(result.coverage, .2);
  assert.equal(result.observedMinutes, 8);
  assert.equal(result.measured.length, 2);
  assert.equal(result.measured[0].distance_estimate_quality, .4);
  assert.equal(result.calibrated, false);
});

test('reconstructed contributions respect missingness and effective quality weights', () => {
  const rows = A.contributions({ screen_score: 80, screen_quality: 1,
    environment_score: 20, environment_quality: .5,
    posture_score: null, posture_quality: null });
  assert.equal(rows.find(item => item.id === 'posture').reconstructedPoints, null);
  assert.equal(rows.find(item => item.id === 'screen').reconstructedPoints, 64);
  assert.equal(rows.find(item => item.id === 'environment').reconstructedPoints, 4);
});

test('contributions keep historical weights separate from the 0.7 design', () => {
  const row = { screen_score: 80, screen_quality: 1,
    environment_score: 20, environment_quality: .5 };
  const old = A.contributions({ ...row, algorithm_version: 'efs-hypothesis-0.5' });
  const current = A.contributions({ ...row, algorithm_version: 'efs-hypothesis-0.7' });
  assert.equal(old.find(item => item.id === 'screen').weight, 20);
  assert.equal(current.find(item => item.id === 'screen').weight, 26);
  assert.equal(current.find(item => item.id === 'environment').weight, 22);
  assert.notEqual(old.find(item => item.id === 'screen').reconstructedPoints,
    current.find(item => item.id === 'screen').reconstructedPoints);
});

test('grant state distinguishes waiting, linked, expiration, and revocation', () => {
  const now = Date.parse('2026-10-10T12:00:00Z');
  const future = { expires_at: '2026-10-11T12:00:00Z', revoked_at: null };
  assert.equal(A.grantState(null, null, now), 'waiting');
  assert.equal(A.grantState(null, future, now), 'linked');
  assert.equal(A.grantState(future, null, now), 'revoked');
  assert.equal(A.grantState({ expires_at: '2026-10-09T12:00:00Z' }, null, now), 'expired');
});

test('append-only answers use the newest receipt for display without mutating the observation', () => {
  const rows = [{ report_id: 'one', subjective_fatigue_nrs: 3 }];
  const events = [
    { id: 'a', report_id: 'one', subjective_fatigue_nrs: 4, received_at: '2026-10-10T08:00:00Z' },
    { id: 'b', report_id: 'one', subjective_fatigue_nrs: 7, received_at: '2026-10-10T09:00:00Z' }
  ];
  assert.deepEqual(A.attachResponses(rows, events).map(row => row.subjective_fatigue_nrs), [7]);
  assert.equal(rows[0].subjective_fatigue_nrs, 3);
});

test('personal reference uses only prior, comparable, same-version received answers', () => {
  const quality = { algorithm_version: 'efs-hypothesis-0.7', screen_quality: 1, environment_quality: 1 };
  const rows = Array.from({ length: 5 }, (_, i) => ({ ...quality,
    report_id: `prior-${i}`, score: 40, observed_at: `2026-10-0${i + 1}T08:00:00Z` }));
  const target = { ...quality, report_id: 'target', score: 50, observed_at: '2026-10-10T08:00:00Z' };
  const events = rows.map((row, i) => ({ id: String(i), report_id: row.report_id,
    subjective_fatigue_nrs: 7, answered_at: `2026-10-0${i + 1}T08:10:00Z`,
    received_at: `2026-10-0${i + 1}T08:11:00Z` }));
  assert.deepEqual(A.personalized(target, [...rows, target], events), { score: 60, samples: 5, offset: 10 });
  assert.deepEqual(A.personalized(rows[4], [...rows, target], events), { score: null, samples: 4, offset: null });
  const revisedAfterTarget = { ...events[0], id: 'later', subjective_fatigue_nrs: 0,
    answered_at: '2026-10-11T08:00:00Z', received_at: '2026-10-11T08:00:01Z' };
  assert.equal(A.personalized(target, [...rows, target], [...events, revisedAfterTarget]).score, 60);
});

test('forward validation uses held-out first receipts and reports missing evidence', () => {
  const quality = { algorithm_version: 'efs-hypothesis-0.7', screen_quality: 1, environment_quality: 1 };
  const rows = Array.from({ length: 10 }, (_, i) => ({ ...quality, report_id: `r-${i}`,
    score: 40, observed_at: `2026-10-${String(i + 1).padStart(2, '0')}T08:00:00Z` }));
  const responses = rows.map((row, i) => ({ id: `a-${i}`, report_id: row.report_id,
    subjective_fatigue_nrs: 7, answered_at: `2026-10-${String(i + 1).padStart(2, '0')}T08:10:00Z`,
    received_at: `2026-10-${String(i + 1).padStart(2, '0')}T08:11:00Z` }));
  const result = A.validation(rows, responses);
  assert.equal(result.sameVersionReports, 10);
  assert.equal(result.eligibleLabels, 10);
  assert.equal(result.evaluated, 5);
  assert.equal(result.rawMae, 30);
  assert.ok(result.personalMae < result.rawMae);
  assert.equal(result.sufficientEvidence, true);
  const correction = { ...responses[0], id: 'later', subjective_fatigue_nrs: 0,
    received_at: '2026-10-12T08:00:00Z' };
  assert.deepEqual(A.validation(rows, [...responses, correction]), result);
  assert.equal(A.validation(rows, []).rawMae, null);
});
