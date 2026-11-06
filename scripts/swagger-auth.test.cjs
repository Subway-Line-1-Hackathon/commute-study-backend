// No npm dependencies or real credentials: exercise the UI with a Supabase/Swagger harness.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const source = fs.readFileSync(path.join(__dirname, '../src/main/resources/swagger-auth/google-auth.js'), 'utf8');

const validSession = (token = 'supabase-access') => ({
  access_token: token, provider_token: 'google-token-not-for-api',
  expires_at: Math.floor(Date.now() / 1000) + 3600, user: { email: 'test@example.invalid' }
});

async function harness({ session = null, code = '', enabled = true, exchangeError = false, signOutError = false,
  pageUrl = 'http://localhost:8080/swagger-ui/index.html' } = {}) {
  const elements = Object.fromEntries(['auth-status', 'google-login', 'google-logout'].map(id => [id, {}]));
  const calls = { authorizations: [], logouts: [], signIns: [], exchanges: [], removed: [] };
  let listener;
  let uiOptions;
  let current = session;
  const auth = {
    getSession: async () => ({ data: { session: current }, error: null }),
    exchangeCodeForSession: async value => {
      calls.exchanges.push(value);
      return { data: { session: current }, error: exchangeError ? new Error('exchange') : null };
    },
    onAuthStateChange: callback => { listener = callback; },
    signInWithOAuth: async value => { calls.signIns.push(value); return {}; },
    signOut: async value => { calls.signOut = value; return { error: signOutError ? new Error('offline') : null }; },
    stopAutoRefresh: () => { calls.stopped = true; }
  };
  function SwaggerUIBundle(options) {
    uiOptions = options;
    queueMicrotask(options.onComplete);
    return {
      preauthorizeApiKey: (name, value) => calls.authorizations.push({ name, value }),
      authActions: { logout: value => calls.logouts.push(value) }
    };
  }
  SwaggerUIBundle.presets = { apis: {} };
  const storage = { removeItem: key => calls.removed.push(key) };
  const window = {
    location: { href: pageUrl + code },
    sessionStorage: storage,
    supabase: { createClient: (url, key, options) => { calls.client = { url, key, options }; return { auth }; } }
  };
  const context = vm.createContext({
    window, document: { getElementById: id => elements[id] }, URL, Date,
    sessionStorage: storage, history: { replaceState: (...args) => { calls.history = args; } },
    SwaggerUIBundle, SwaggerUIStandalonePreset: {},
    fetch: async () => ({ ok: true, json: async () => ({ enabled, url: 'https://test.supabase.co', publishableKey: 'sb_publishable_test' }) })
  });
  await vm.runInContext(source, context);
  return {
    elements, calls, uiOptions,
    setSession: next => { current = next; listener('TOKEN_REFRESHED', next); }
  };
}

test('Google login uses PKCE, tab storage, and the same-server callback', async () => {
  const h = await harness();
  assert.equal(h.calls.client.options.auth.flowType, 'pkce');
  assert.equal(h.calls.client.options.auth.detectSessionInUrl, false);
  assert.ok(h.calls.client.options.auth.storage);
  await h.elements['google-login'].onclick();
  assert.equal(h.calls.signIns[0].provider, 'google');
  assert.equal(h.calls.signIns[0].options.redirectTo, 'http://localhost:8080/swagger-ui/index.html');
});

test('PKCE callback removes the code and applies Supabase access token, not Google token', async () => {
  const h = await harness({ session: validSession(), code: '?code=single-use-code' });
  assert.equal(h.calls.exchanges[0], 'single-use-code');
  assert.equal(h.calls.history[2], '/swagger-ui/index.html');
  assert.equal(h.calls.authorizations.at(-1).value, 'supabase-access');
  const request = await h.uiOptions.requestInterceptor({ url: '/api/auth/me', headers: {} });
  assert.equal(request.headers.Authorization, 'Bearer supabase-access');
  assert.match(h.elements['auth-status'].textContent, /test@example.invalid/);
});

test('refresh updates Swagger and expired credentials block API execution', async () => {
  const h = await harness({ session: validSession() });
  h.setSession(validSession('refreshed-access'));
  const request = await h.uiOptions.requestInterceptor({ url: '/api/users/me/nickname', headers: {} });
  assert.equal(request.headers.Authorization, 'Bearer refreshed-access');
  h.setSession({ ...validSession(), expires_at: 1 });
  await assert.rejects(h.uiOptions.requestInterceptor({ url: '/api/auth/me', headers: {} }));
  assert.match(h.elements['auth-status'].textContent, /만료/);
});

test('managed JWT is stripped from external origins and non-API URLs', async () => {
  const h = await harness({ session: validSession() });
  for (const url of ['https://other.example/api/auth/me', '/actuator/health', '/api-docs']) {
    const request = await h.uiOptions.requestInterceptor({ url, headers: { Authorization: 'Bearer supabase-access' } });
    assert.equal(request.headers.Authorization, undefined);
  }
});

test('logout removes Swagger authorization and ignores late refresh events', async () => {
  const h = await harness({ session: validSession() });
  await h.elements['google-logout'].onclick();
  assert.ok(h.calls.stopped);
  assert.equal(h.calls.signOut.scope, 'local');
  assert.ok(h.calls.logouts.length);
  const count = h.calls.authorizations.length;
  h.setSession(validSession('late-refresh'));
  assert.equal(h.calls.authorizations.length, count);
  assert.ok(h.calls.removed.includes('commute-swagger-auth'));
  const request = await h.uiOptions.requestInterceptor({ url: '/api/auth/me', headers: {} });
  assert.equal(request.headers.Authorization, undefined);
});

test('failed remote logout still clears local session and Swagger', async () => {
  const h = await harness({ session: validSession(), signOutError: true });
  await h.elements['google-logout'].onclick();
  assert.ok(h.calls.removed.includes('commute-swagger-auth'));
  assert.ok(h.calls.logouts.length);
  assert.match(h.elements['auth-status'].textContent, /원격 로그아웃/);
});

test('manual Authorize works without Google configuration or session', async () => {
  for (const enabled of [false, true]) {
    const h = await harness({ enabled });
    const request = await h.uiOptions.requestInterceptor({ url: '/api/auth/me', headers: { Authorization: 'Bearer manual-token' } });
    assert.equal(request.headers.Authorization, 'Bearer manual-token');
    assert.equal(h.uiOptions.persistAuthorization, false);
  }
});

test('failed callback leaves a working retry button and does not print raw error data', async () => {
  const h = await harness({ code: '?code=bad-code', exchangeError: true });
  assert.equal(h.elements['google-login'].disabled, false);
  await h.elements['google-login'].onclick();
  assert.equal(h.calls.signIns.length, 1);
  assert.doesNotMatch(h.elements['auth-status'].textContent, /bad-code/);
});

test('cancelled OAuth callback clears the URL and offers retry without displaying raw provider errors', async () => {
  const h = await harness({ code: '?error=access_denied&error_description=private-provider-detail' });
  assert.equal(h.calls.history[2], '/swagger-ui/index.html');
  assert.equal(h.calls.exchanges.length, 0);
  assert.doesNotMatch(h.elements['auth-status'].textContent, /private-provider-detail/);
  await h.elements['google-login'].onclick();
  assert.equal(h.calls.signIns.length, 1);
});

test('local HTTP and Railway HTTPS keep the callback and automatic Bearer header on the correct origin', async () => {
  for (const origin of ['http://localhost:8080', 'https://commute-study-backend-production.up.railway.app']) {
    const pageUrl = origin + '/swagger-ui/index.html';
    const h = await harness({ pageUrl, session: validSession() });
    const request = await h.uiOptions.requestInterceptor({ url: origin + '/api/auth/me', headers: {} });
    assert.equal(request.url, origin + '/api/auth/me');
    assert.equal(request.headers.Authorization, 'Bearer supabase-access');
    await h.elements['google-logout'].onclick();
    await h.elements['google-login'].onclick();
    assert.equal(h.calls.signIns.at(-1).options.redirectTo, pageUrl);
    assert.equal(h.uiOptions.configUrl, origin + '/v3/api-docs/swagger-config');
  }
});
