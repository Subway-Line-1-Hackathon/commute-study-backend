/* Supabase session.access_token is the API credential, never provider_token. */
(async function () {
  'use strict';
  const status = document.getElementById('auth-status');
  const login = document.getElementById('google-login');
  const logout = document.getElementById('google-logout');
  const here = new URL(window.location.href);
  const code = here.searchParams.get('code');
  const callbackError = here.searchParams.has('error') || here.searchParams.has('error_description');
  // Remove authorization codes/errors/fragments immediately. No access token URL flow.
  if (here.search || here.hash) history.replaceState(null, '', here.pathname);
  const callbackUrl = new URL('./index.html', here).href;
  const apiRoot = new URL('../api/', here);
  let client;
  let session = null;
  let managed = false;
  let locallySignedOut = false;

  function clearManaged() {
    if (managed) window.ui.authActions.logout(['bearerAuth']);
    managed = false;
  }

  function applySession(next) {
    session = next;
    if (next) {
      window.ui.preauthorizeApiKey('bearerAuth', next.access_token);
      managed = true;
      status.textContent = '로그인됨: ' + (next.user?.email || 'Google 사용자');
    } else {
      clearManaged();
      status.textContent = '로그인하지 않았습니다. Authorize에 토큰을 직접 입력할 수도 있습니다.';
    }
    logout.hidden = !next;
    login.hidden = !!next;
    login.disabled = false;
  }

  async function authorizeRequest(request) {
    const target = new URL(request.url, here);
    const isApi = target.origin === apiRoot.origin && target.pathname.startsWith(apiRoot.pathname);
    // Swagger already applies its security scheme before this interceptor runs.
    if (managed && !isApi) {
      for (const name of Object.keys(request.headers)) {
        if (name.toLowerCase() === 'authorization') delete request.headers[name];
      }
    }
    // Never forward the session token to another origin or non-API resource.
    if (client && session && isApi) {
      const { data, error } = await client.auth.getSession();
      if (error || !data.session || data.session.expires_at * 1000 <= Date.now()) {
        applySession(null);
        status.textContent = '로그인이 만료됐습니다. Google로 다시 로그인하세요.';
        throw new Error('Google 로그인이 만료됐습니다. 다시 로그인하세요.');
      }
      applySession(data.session);
      request.headers.Authorization = 'Bearer ' + data.session.access_token;
    }
    return request;
  }

  let ready;
  const uiReady = new Promise(resolve => { ready = resolve; });
  window.ui = SwaggerUIBundle({
    configUrl: new URL('../v3/api-docs/swagger-config', here).href,
    dom_id: '#swagger-ui',
    presets: [SwaggerUIBundle.presets.apis, SwaggerUIStandalonePreset],
    layout: 'StandaloneLayout',
    persistAuthorization: false,
    requestInterceptor: authorizeRequest,
    onComplete: () => ready()
  });

  try {
    const response = await fetch(new URL('./auth-config', here), { cache: 'no-store' });
    if (!response.ok) throw new Error('config');
    const config = await response.json();
    if (!config.enabled) {
      status.textContent = 'Google 로그인 설정이 없습니다. SUPABASE_PUBLISHABLE_KEY 또는 SUPABASE_ANON_KEY를 설정하세요. Authorize는 사용 가능합니다.';
      return;
    }
    client = window.supabase.createClient(config.url, config.publishableKey, {
      auth: {
        flowType: 'pkce', detectSessionInUrl: false, autoRefreshToken: true,
        persistSession: true, storage: window.sessionStorage,
        storageKey: 'commute-swagger-auth'
      }
    });
    await uiReady;
    // Keep callback synchronous: Supabase auth callbacks must not re-enter auth APIs.
    client.auth.onAuthStateChange((_event, next) => {
      if (!locallySignedOut) applySession(next);
      else sessionStorage.removeItem('commute-swagger-auth');
    });

    login.onclick = async () => {
      login.disabled = true;
      status.textContent = 'Google 로그인으로 이동 중…';
      try {
        const { error } = await client.auth.signInWithOAuth({
          provider: 'google', options: { redirectTo: callbackUrl }
        });
        if (error) throw new Error('oauth');
      } catch {
        login.disabled = false;
        status.textContent = '로그인을 시작하지 못했습니다. Supabase Google provider와 Redirect URL 설정을 확인하세요.';
      }
    };
    logout.onclick = async () => {
      logout.disabled = true;
      locallySignedOut = true;
      client.auth.stopAutoRefresh();
      applySession(null);
      try {
        const { error } = await client.auth.signOut({ scope: 'local' });
        if (error) throw new Error('logout');
        applySession(null);
      } catch {
        // Clear the local credential even when the remote logout request fails.
        sessionStorage.removeItem('commute-swagger-auth');
        applySession(null);
        status.textContent = '이 탭의 인증을 해제했습니다. 원격 로그아웃은 확인하지 못했습니다.';
      } finally {
        logout.disabled = false;
      }
    };
    if (callbackError) throw new Error('oauth');
    const result = code
      ? await client.auth.exchangeCodeForSession(code)
      : await client.auth.getSession();
    if (result.error) throw new Error('session');
    applySession(result.data.session);
  } catch {
    clearManaged();
    session = null;
    login.disabled = !login.onclick;
    status.textContent = '로그인에 실패했습니다. 같은 탭에서 다시 시도하거나 새로고침하세요. Supabase Redirect URL 설정도 확인하세요.';
  }
}());
