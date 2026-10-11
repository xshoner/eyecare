/* Preserve the paired browser identity across token refresh and tabs. */
(function (root, factory) {
  const api = factory();
  if (typeof module === 'object' && module.exports) module.exports = api;
  else root.EyeCareBrowserSession = api;
})(typeof window === 'undefined' ? globalThis : window, function () {
  const AUTH_KEY = 'eyecare_browser_auth_v1';
  const uuid = value => typeof value === 'string' &&
    /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(value);
  function sessionError(code) {
    const error = new Error(code);
    error.code = code;
    return error;
  }
  function create({ request, storage, now = () => Date.now(), locks = null,
    delay = ms => new Promise(resolve => setTimeout(resolve, ms)) }) {
    let memory = null;
    function saved() {
      try { return JSON.parse(storage.getItem(AUTH_KEY) || 'null'); }
      catch (_) { throw sessionError('storage_unavailable'); }
    }
    function save(value) {
      try { storage.setItem(AUTH_KEY, JSON.stringify(value)); }
      catch (_) { throw sessionError('storage_unavailable'); }
      memory = value;
    }
    function checkStorage() {
      try {
        storage.setItem(`${AUTH_KEY}_probe`, '1');
        storage.removeItem(`${AUTH_KEY}_probe`);
      } catch (_) { throw sessionError('storage_unavailable'); }
    }
    function valid(value) {
      return value && uuid(value.user_id) && value.access_token && value.refresh_token &&
        Number.isFinite(value.expires_at_ms);
    }
    async function refresh(previous) {
      const result = await request('POST', '/auth/v1/token?grant_type=refresh_token',
        { refresh_token: previous.refresh_token });
      if (!uuid(result?.user?.id) || result.user.id !== previous.user_id ||
        !result.access_token || !result.refresh_token || !Number.isFinite(Number(result.expires_in)))
        throw sessionError('identity_mismatch');
      const fresh = { user_id: result.user.id, access_token: result.access_token,
        refresh_token: result.refresh_token, expires_at_ms: now() + Number(result.expires_in) * 1000 };
      save(fresh);
      return fresh;
    }
    async function inside(force) {
      checkStorage();
      let old = saved() || memory;
      if (old && !valid(old)) throw sessionError('session_recovery_needed');
      if (old && !force && old.expires_at_ms > now() + 60_000) return old;
      if (old) {
        try { return await refresh(old); }
        catch (error) {
          if (error.status !== 400 && error.status !== 401) throw error;
          // Another tab may have rotated the refresh token. Reuse that same identity.
          await delay(200);
          const latest = saved();
          if (valid(latest) && latest.user_id === old.user_id &&
              latest.refresh_token !== old.refresh_token) {
            if (latest.expires_at_ms > now() + 60_000) { memory = latest; return latest; }
            try { return await refresh(latest); } catch (_) { /* Keep the paired identity. */ }
          }
          throw sessionError('session_recovery_needed');
        }
      }
      // Only a genuinely new browser profile may create an anonymous identity.
      const result = await request('POST', '/auth/v1/signup', { data: {}, gotrue_meta_security: {} });
      if (!uuid(result?.user?.id) || !result.access_token || !result.refresh_token ||
        !Number.isFinite(Number(result.expires_in))) throw sessionError('invalid_auth_session');
      const fresh = { user_id: result.user.id, access_token: result.access_token,
        refresh_token: result.refresh_token, expires_at_ms: now() + Number(result.expires_in) * 1000 };
      save(fresh);
      return fresh;
    }
    async function ensure(force = false) {
      return locks?.request ? locks.request(AUTH_KEY, () => inside(force)) : inside(force);
    }
    return { ensure, saved };
  }
  return { AUTH_KEY, create };
});
