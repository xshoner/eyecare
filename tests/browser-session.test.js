const test = require('node:test');
const assert = require('node:assert/strict');
const { AUTH_KEY, create } = require('../browser-session.js');
const id = '21c85991-85bd-42d6-821e-4639c91a93f1';
const next = '594899e3-aed5-457e-a46f-b13fc6c4116b';
const store = () => {
  const values = new Map();
  return { getItem: key => values.get(key) || null,
    setItem: (key, value) => values.set(key, value), removeItem: key => values.delete(key) };
};
const session = (userId, refresh = 'old') => ({ user_id: userId,
  access_token: 'access', refresh_token: refresh, expires_at_ms: 100 });

test('first browser signup is saved and later checks keep the same identity', async () => {
  const storage = store();
  let signups = 0;
  const manager = create({ storage, now: () => 1000,
    request: async () => { signups++; return { user: { id }, access_token: 'access',
      refresh_token: 'refresh', expires_in: 3600 }; } });
  assert.equal((await manager.ensure()).user_id, id);
  assert.equal((await manager.ensure()).user_id, id);
  assert.equal(signups, 1);
  assert.equal(JSON.parse(storage.getItem(AUTH_KEY)).user_id, id);
});

test('rejected refresh never silently creates a new browser account', async () => {
  const storage = store(); storage.setItem(AUTH_KEY, JSON.stringify(session(id)));
  let signups = 0;
  const manager = create({ storage, now: () => 1000, delay: async () => {},
    request: async (_method, path) => { if (path.includes('signup')) signups++;
      throw Object.assign(new Error('refresh denied'), { status: 401 }); } });
  await assert.rejects(manager.ensure(), { code: 'session_recovery_needed' });
  assert.equal(signups, 0);
  assert.equal(JSON.parse(storage.getItem(AUTH_KEY)).user_id, id);
});

test('another tab rotation reuses the same owner and never signs up', async () => {
  const storage = store(); storage.setItem(AUTH_KEY, JSON.stringify(session(id)));
  let signups = 0;
  const manager = create({ storage, now: () => 1000,
    delay: async () => { storage.setItem(AUTH_KEY, JSON.stringify({ ...session(id, 'rotated'),
      expires_at_ms: 4_000_000 })); },
    request: async (_method, path) => { if (path.includes('signup')) signups++;
      throw Object.assign(new Error('stale token'), { status: 400 }); } });
  assert.equal((await manager.ensure()).refresh_token, 'rotated');
  assert.equal(signups, 0);
});

test('identity-changing refresh is blocked even after page reload', async () => {
  const storage = store(); storage.setItem(AUTH_KEY, JSON.stringify(session(id)));
  const manager = create({ storage, now: () => 1000,
    request: async () => ({ user: { id: next }, access_token: 'new',
      refresh_token: 'new-refresh', expires_in: 3600 }) });
  await assert.rejects(manager.ensure(), { code: 'identity_mismatch' });
  assert.equal(JSON.parse(storage.getItem(AUTH_KEY)).user_id, id);
});

test('blocked storage prevents anonymous ID creation', async () => {
  let calls = 0;
  const manager = create({ storage: { getItem: () => null, setItem: () => { throw Error('blocked'); },
    removeItem: () => {} }, request: async () => { calls++; } });
  await assert.rejects(manager.ensure(), { code: 'storage_unavailable' });
  assert.equal(calls, 0);
});
