const test = require('node:test');
const assert = require('node:assert/strict');
const A = require('../dashboard-core.js');

test('synthetic records never enter real charts or NRS pairs', () => {
  const rows = [
    { algorithm_version: 'synthetic-test-0.5.2', score: 90, subjective_fatigue_nrs: 10 },
    { algorithm_version: 'efs-hypothesis-0.5', score: 40, subjective_fatigue_nrs: null },
    { algorithm_version: 'efs-hypothesis-0.5', score: null, subjective_fatigue_nrs: 8 },
    { algorithm_version: 'efs-hypothesis-0.5', score: 55, subjective_fatigue_nrs: 7 }
  ];
  const { real, synthetic } = A.partition(rows);
  assert.equal(real.length, 3);
  assert.equal(synthetic.length, 1);
  assert.deepEqual(A.paired(real).map(row => row.score), [55]);
  assert.equal(A.peak(real).score, 55);
});

test('reconstructed contributions respect missingness and effective quality weights', () => {
  const rows = A.contributions({ screen_score: 80, screen_quality: 1,
    environment_score: 20, environment_quality: .5,
    posture_score: null, posture_quality: null });
  assert.equal(rows.find(item => item.id === 'posture').reconstructedPoints, null);
  assert.equal(rows.find(item => item.id === 'screen').reconstructedPoints, 64);
  assert.equal(rows.find(item => item.id === 'environment').reconstructedPoints, 4);
});

test('grant state distinguishes waiting, linked, expiration, and revocation', () => {
  const now = Date.parse('2026-10-10T12:00:00Z');
  const future = { expires_at: '2026-10-11T12:00:00Z', revoked_at: null };
  assert.equal(A.grantState(null, null, now), 'waiting');
  assert.equal(A.grantState(null, future, now), 'linked');
  assert.equal(A.grantState(future, null, now), 'revoked');
  assert.equal(A.grantState({ expires_at: '2026-10-09T12:00:00Z' }, null, now), 'expired');
});
