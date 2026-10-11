const test = require('node:test');
const assert = require('node:assert/strict');
const W = require('../algorithm-core');

const row = {
  screen_score: 60, screen_quality: .8, environment_score: 30, environment_quality: .5,
  posture_score: null, posture_quality: null, usage_score: 40, usage_quality: .9,
  eyes_score: 80, eyes_quality: .6, screen_change: .08, continuous_minutes: 70,
  estimated_fields: []
};

test('the published baseline weights sum to 100 and use quality-weighted available domains', () => {
  const baseline = W.base();
  assert.equal(W.normalized(baseline).weights.reduce((n, item) => n + item.share, 0), 100);
  const result = W.simulate(row, baseline);
  const expected = Math.round((60 * 20 * .8 + 30 * 10 * .5 + 40 * 20 * .9 + 80 * 35 * .6) /
    (20 * .8 + 10 * .5 + 20 * .9 + 35 * .6));
  assert.equal(result.score, expected);
  assert.equal(result.coverage, 85);
  assert.equal(result.contributions.find(item => item.id === 'posture').points, null);
});

test('all disabled or zero weight is invalid instead of a false zero score', () => {
  assert.match(W.validate(W.base().map(item => ({ ...item, enabled: false }))), /양수/);
  assert.match(W.validate(W.base().map(item => ({ ...item, weight: 0 }))), /양수/);
  assert.equal(W.simulate(row, W.base().map(item => ({ ...item, weight: 0 }))).score, null);
});

test('range errors, duplicate IDs, and duplicate custom labels are rejected', () => {
  assert.match(W.validate([...W.base(), { id: 'screen', enabled: true, weight: 5 }]), /중복/);
  assert.match(W.validate(W.base().map(item => ({ ...item, weight: 101 }))), /0~100/);
  const custom = W.addCustom(W.base(), { label: '휴식', dataNeeded: '시각', formula: '중앙값' });
  assert.ok(custom);
  assert.equal(W.addCustom(W.base(), { label: '화면 자극', dataNeeded: '값', formula: '합' }), null);
  assert.equal(W.addCustom(custom, { label: '휴식', dataNeeded: '시각', formula: '중앙값' }), null);
  assert.match(W.validate([...custom, { ...custom.at(-1), id: 'custom:second' }]), /같은 이름/);
});

test('a stored metric is added once, can be removed, and never edits the original row', () => {
  const original = JSON.stringify(row);
  const added = W.addStored(W.base(), 'screen_change');
  assert.equal(W.addStored(added, 'screen_change'), null);
  assert.notEqual(W.simulate({ ...row, screen_change: .12 }, added).score,
    W.simulate({ ...row, screen_change: .12 }, W.base()).score);
  assert.equal(W.simulate(row, added.filter(item => item.id !== 'screen_change')).score,
    W.simulate(row, W.base()).score);
  assert.equal(JSON.stringify(row), original);
});

test('missing, estimated, invalid, and unsupported custom metrics do not create measurements', () => {
  const added = W.addStored(W.base(), 'screen_change');
  const missing = W.simulate({ ...row, screen_change: null }, added);
  const estimated = W.simulate({ ...row, estimated_fields: ['screen_change'] }, added);
  const invalid = W.simulate({ ...row, screen_change: 200 }, added);
  assert.equal(missing.coverage, estimated.coverage);
  assert.equal(missing.coverage, invalid.coverage);
  assert.equal(missing.contributions.find(item => item.id === 'screen_change').points, null);
  const custom = W.addCustom(W.base(), { label: '새 센서', dataNeeded: '미구현', formula: '예상값' });
  custom.at(-1).enabled = true; custom.at(-1).weight = 20;
  assert.equal(W.simulate(row, custom).contributions.at(-1).points, null);
});

test('reset and cancellation can restore the immutable baseline or saved edit', () => {
  const baseline = W.base();
  const saved = W.copy(baseline); saved[0].weight = 35;
  const draft = W.copy(saved); draft[0].weight = 90;
  assert.equal(W.copy(saved)[0].weight, 35);
  assert.deepEqual(W.base(), baseline);
  assert.equal(W.base()[0].weight, 20);
});
