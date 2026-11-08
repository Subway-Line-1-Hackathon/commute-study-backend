# commute-study-api

Spring Boot 4.1.2 + PostgreSQL(Supabase) 기반 API 서버. Railway 로 배포한다.

## 문서

| 문서                                                         | 내용                                                                                           |
| ------------------------------------------------------------ | ---------------------------------------------------------------------------------------------- |
| [백엔드 개발 명세서](docs/백엔드_개발_명세서.md)             | 범위, 시스템 구성, 인증, 설정값, 데이터 모델, API, 핵심 로직, 개발 순서, 완료 체크리스트       |
| [AI 서버 ↔ 백엔드 API 명세](docs/AI_서버_백엔드_API_명세.md) | 백엔드가 AI 서버(FastAPI)를 호출하는 API의 요청·응답, 에러, 타임아웃, AI 담당과 합의 필요 항목 |

슬라이스 5의 신규 API, CurrentUser 재사용 방법과 검증 범위는
[사용자 자동 생성·내 정보 조회 안내](docs/슬라이스5_사용자_조회.md)를 참고한다.

슬라이스 6의 설정 저장·조회 API는 [닉네임·통학 설정 안내](docs/슬라이스6_통학_설정.md)를 참고한다.

슬라이스 7의 과목 API와 D-Day·소유권 검증은 [과목 등록·목록 안내](docs/슬라이스7_과목.md)를 참고한다.

슬라이스 8의 API·Storage 환경변수·슬라이스 9 연동·PNG 최대 20장 계약은
[자료 업로드·단원화 안내](docs/슬라이스8_자료_업로드.md)를 참고한다.

## 요구 사항

- JDK 21
- Supabase 프로젝트 (DB 공용으로 팀원과 공유)

## 로컬 실행

1. 프로젝트 루트의 `.env.example` 을 복사해서 같은 위치에 `.env` 로 저장한다.
   (이 파일은 `.gitignore` 에 등록되어 깃에 올라가지 않는다.)
2. Supabase 대시보드 > **Project Settings > Database > Connection string** 에서 값을 확인해 `.env` 에 채운다.
   - `spring.datasource.*` : **Connection pooling (Transaction mode, 6543 포트)** 사용을 권장한다. (서버리스/다중 인스턴스 환경에서 커넥션 수 제한 문제를 피할 수 있음)
   - `spring.flyway.*` : **Session/Direct connection (5432 포트)** 를 사용한다. 6543 Transaction 모드는 Flyway가 거는 advisory lock(세션 단위)과 호환되지 않아 마이그레이션 접속을 분리했다.
   - DB 비밀번호는 깃/슬랙 등 공개 채널이 아닌 팀원과의 별도 채널(1:1 DM 등)로 공유한다.
   - `.env`의 키는 **소문자 + 점 표기**(`spring.datasource.url` 등)로 적어야 한다. `.env`는 properties 파일로 읽히기 때문에, `SPRING_DATASOURCE_URL`처럼 대문자+언더스코어로 적으면 매핑이 안 된다. (아래 Railway 배포 섹션의 환경변수 표기와 다르니 주의)
   - `SUPABASE_URL`은 예외적으로 **대문자 그대로** 쓴다(`application.yaml`이 `${SUPABASE_URL}`로 직접 참조). Supabase 대시보드 > Project Settings > API > Project URL 값. 구글 로그인(JWT 검증)에 필수라, 빠지면 `PlaceholderResolutionException`으로 기동이 실패한다.
   - `AI_SERVER_URL`, `AI_INTERNAL_KEY`도 대문자 그대로 쓴다. 둘 다 기본값(`http://localhost:8000`, 빈 값)이 있어 없어도 기동은 되지만, AI 서버를 실제로 호출하려면 채워야 한다. 로컬에서는 `commute-study-ai`를 `AI_MOCK=true`로 직접 띄워서 연결한다(`.env.example` 참고).
3. 실행

   ```bash
   ./gradlew bootRun
   ```

   앱이 기동되면 `src/main/resources/db/migration/` 아래의 Flyway 마이그레이션이 자동으로 Supabase에 적용된다 (아래 "DB 스키마 운영" 참고).

## 배포 (Railway)

- 공개 URL: https://commute-study-backend-production.up.railway.app
  - 헬스체크: https://commute-study-backend-production.up.railway.app/actuator/health
  - Swagger: https://commute-study-backend-production.up.railway.app/swagger-ui/index.html

Railway 프로젝트의 **Variables** 탭에 아래 환경변수를 등록한다. (Supabase 접속 정보와 동일, URL에 `?sslmode=require` 포함)

여기서는 실제 OS 환경변수로 주입되므로, 로컬 `.env`와 달리 **대문자 + 언더스코어 표기**를 그대로 쓴다.

| 변수명                       | 설명                                                                                            |
| ---------------------------- | ----------------------------------------------------------------------------------------------- |
| `SPRING_DATASOURCE_URL`      | Supabase JDBC URL (6543, Transaction pooling)                                                   |
| `SPRING_DATASOURCE_USERNAME` | Supabase DB 사용자명                                                                            |
| `SPRING_DATASOURCE_PASSWORD` | Supabase DB 비밀번호                                                                            |
| `SPRING_FLYWAY_URL`          | Supabase JDBC URL (5432, Session/Direct)                                                        |
| `SPRING_FLYWAY_USER`         | Supabase DB 사용자명 (datasource와 동일)                                                        |
| `SPRING_FLYWAY_PASSWORD`     | Supabase DB 비밀번호 (datasource와 동일)                                                        |
| `SUPABASE_URL`               | 구글 로그인 JWT 검증용 Supabase Project URL. **빠지면 기동 자체가 실패한다**                    |
| `AI_SERVER_URL`              | AI 서버 주소 (같은 Railway 프로젝트 내부망, 포트 포함)                                          |
| `AI_INTERNAL_KEY`            | AI 서버의 같은 이름 변수와 같은 값. 로그에 남기지 않는다                                        |
| `SPRING_PROFILES_ACTIVE`     | `prod` — 운영에서 `show-sql`을 끄는 프로파일(`application-prod.yaml`) 적용                      |
| `APP_CORS_ALLOWED_ORIGINS`   | 허용할 프론트 출처의 쉼표 목록 (예: `https://frontend.example.com,https://preview.example.com`) |

CORS는 `/api/**`에 적용되며 GET, POST, PUT, PATCH, DELETE, OPTIONS를 허용한다.
`app.cors.allowed-origins`의 기본값은 `http://127.0.0.1:5500`, `http://127.0.0.1:5501`,
`http://localhost:5500`, `http://localhost:5501`이다. 운영에서는 프론트 담당과 확정한 출처를
`APP_CORS_ALLOWED_ORIGINS`로 주입한다. 설정한 목록은 기본값을 대체하며, 출처는 경로나 끝 슬래시 없이 입력한다.
로컬 `.env`에서는 `app.cors.allowed-origins`로 설정할 수 있다.

Railway는 자체적으로 `PORT` 환경변수를 주입하며, `application.yaml` 의 `server.port: ${PORT:8080}` 설정이 이를 자동으로 반영한다. 별도 설정 불필요.

빌드는 Railway의 기본 Gradle 빌드(`./gradlew bootJar`)를 사용하면 된다.

Railway가 자동 생성하는 Start Command는 멀티모듈 구조를 가정한 와일드카드(`*/build/libs/*jar`)를 쓰는데, 이 레포는 단일 모듈이라 경로가 맞지 않아 컨테이너 기동 시 `ls: cannot access` 에러로 실패한다. **Settings > Deploy > Custom Start Command**에 아래를 직접 지정해야 한다.

```
java -jar build/libs/commute-study-api-0.0.1-SNAPSHOT.jar
```

## Swagger UI에서 Google 로그인 (Issue #42)

별도 HTML 파일이나 프론트 서버 없이 Spring Boot의 `/swagger-ui/index.html`에서
Google 로그인, 로그인 상태 확인, 로그아웃, API Execute를 사용할 수 있다.
기존 **Authorize** 버튼을 통한 Access Token 수동 입력도 유지된다.

### 환경별 설정

- `SUPABASE_URL`: 현재 JWT 검증에 사용하는 것과 **같은** Supabase 프로젝트 URL.
- `SUPABASE_PUBLISHABLE_KEY`: Supabase 프로젝트의 **publishable 키** (`sb_publishable_...`).
  구형 프로젝트는 이 항목 대신 `SUPABASE_ANON_KEY`에 legacy **anon 키**를 설정한다.
  두 키가 모두 설정되면 `SUPABASE_PUBLISHABLE_KEY`가 우선한다.
- 로컬에서는 루트 `.env`, Railway에서는 Variables에 설정하고 앱을 재시작한다.
  `service_role`, `sb_secret_*`, Google Client Secret, JWT Secret은 이 공개 키 항목에 넣지 않는다.
  잘못된 키나 누락된 키는 브라우저로 전달하지 않고 Google 로그인만 비활성화한다.
- 키를 넣기 전에도 기존 수동 Authorize와 API 인증은 사용할 수 있다.

### Supabase / Google 대시보드 설정

1. Supabase **Authentication → Sign In / Providers → Google**에서 Google provider를 활성화한다.
   Google OAuth Client ID/Secret은 Supabase 대시보드에만 저장한다.
2. Google Cloud OAuth 클라이언트의 **Authorized redirect URIs**에는 Supabase가 표시하는
   `https://<project-ref>.supabase.co/auth/v1/callback`을 등록한다. 앱 Swagger 주소와 다르다.
3. Supabase **Authentication → URL Configuration → Redirect URLs**에는 아래 앱 주소를
   정확히 등록한다. 코드 콜백은 별도 경로 없이 이 페이지에서 처리한다.

   ```text
   http://localhost:8080/swagger-ui/index.html
   https://commute-study-backend-production.up.railway.app/swagger-ui/index.html
   ```

   `127.0.0.1`, 다른 포트, 별도 배포 도메인 또는 context path를 사용하면 그 주소도 별도로 등록한다.
   운영에서는 정확한 HTTPS 주소를 사용한다. Site URL만 등록하는 것으로 Redirect URLs를 대신하지 않는다.
   Google 앱이 Testing 상태라면 테스트할 Google 계정도 Google Cloud의 테스트 사용자에 등록한다.

### 실제 로그인 확인

1. 설정 후 앱을 실행하고 일반 브라우저의 **같은 탭**에서
   `http://localhost:8080/swagger-ui/index.html`을 연다.
2. **Google 로그인**을 눌러 계정을 선택한다. 콜백 후 상단에 이메일과 로그아웃 버튼이 표시된다.
3. `GET /api/auth/me`의 **Try it out → Execute**를 누른다. 수동 토큰 입력 없이 200과 본인 UUID를 확인한다.
4. `GET /api/users/me/nickname`도 확인한다. PUT은 실제 닉네임을 변경하므로 변경할 의사가 있는
   테스트 계정에서만 `{"nickname":"동현"}`으로 실행한다.
5. **로그아웃**을 누르고 API를 다시 호출하면 401이어야 한다. 이후 Authorize에 별도 Supabase
   Access Token을 직접 넣으면 기존 방식으로 호출할 수 있다.

### 처리 방식과 한계

- SDK의 `signInWithOAuth(provider: google)`를 `flowType: pkce`로 사용하고, 콜백의 `code`를
  `exchangeCodeForSession`으로 교환한다. Swagger 표준 OAuth2 provider 설정으로 대체하지 않는다.
- API에는 **Supabase session.access_token**만 사용한다. Google `provider_token`을 사용하지 않는다.
  기존 `bearerAuth` 스키마에 토큰을 적용하고 같은 origin의 `/api/` 요청에만 자동 전송한다.
- Access/refresh token과 PKCE verifier는 SDK가 **sessionStorage(탭 범위)**로 관리한다.
  자동 갱신된 토큰을 Swagger에도 반영한다. 갱신 실패·만료 시 API 실행을 중단하고 재로그인을 안내한다.
  토큰은 로그/주소창에 기록하지 않고 콜백의 코드/오류 URL도 즉시 지운다.
- 로그아웃은 이 탭에서 사용하는 Supabase 세션을 대상으로 하며 Google 계정 자체를 로그아웃하지 않는다.
  원격 로그아웃 실패 시에도 이 탭의 저장 토큰·Swagger 인증을 해제한다.
- 로그인 취소/콜백 오류 시 같은 탭에서 다시 시도한다. 탭을 바꾸거나 탭 저장소를 차단하면
  PKCE verifier를 찾지 못할 수 있다. 공유 PC에서는 사용 후 로그아웃하고 탭을 닫는다.
- 문서 자산과 공개 키 설정은 기존 `/swagger-ui/**` 공개 범위 안에서 제공한다.
  Spring Security, ES256 검증, CORS, DB 마이그레이션 및 기존 API 로직은 변경하지 않는다.
- Supabase SDK **2.119.0** UMD와 MIT 라이선스를 `src/main/resources/swagger-auth/`에 포함했다.
  브라우저가 런타임에 CDN을 호출하지 않는다. 원본은
  `https://cdn.jsdelivr.net/npm/@supabase/supabase-js@2.119.0/dist/umd/supabase.js`이며 SHA-256은
  `a867edbcfb029370aeec7223dd6180b560b621fbe3bc64c6584fb0369d1b7bce`다.

자동 테스트는 `./gradlew test`와 `node --test scripts/swagger-auth.test.cjs`로 실행한다.
JS 테스트는 가짜 SDK 세션으로 로그인 요청·PKCE 콜백·갱신·자동 헤더·로그아웃·수동 인증을 검증한다.
실제 Google 계정 선택과 Supabase 대시보드 설정은 위 절차로 별도 확인해야 한다.
공식 참고: [Supabase PKCE](https://supabase.com/docs/guides/auth/sessions/pkce-flow),
[Google 로그인](https://supabase.com/docs/guides/auth/social-login/auth-google).

## Railway HTTPS 프록시와 Swagger Failed to fetch 진단

Railway는 외부 HTTPS 연결을 종료한 뒤 앱 컨테이너에는 HTTP로 전달한다.
`server.forward-headers-strategy: framework`로 Spring이 `X-Forwarded-Proto`,
`X-Forwarded-Host`, `X-Forwarded-Port` 및 표준 `Forwarded` 헤더를 먼저 반영하도록 한다.
이 설정은 springdoc의 `servers` URL과 Spring Security의 동일 출처 판단에 적용된다.
운영 도메인을 OpenAPI에 하드코딩하지 않으며, forwarded 헤더 없는 로컬 HTTP도 유지한다.
forwarded 헤더는 신뢰하는 프록시가 외부 입력을 정리해 전달하는 것을 전제로 한다.

- 최신 코드 배포 시 별도 환경변수는 필수가 아니다. Railway Variables에 기존
  `SERVER_FORWARD_HEADERS_STRATEGY`가 `none`/`native`로 있으면 제거하거나 `framework`로 변경한다.
  코드 배포 전 설정으로 우선 적용하려면 `SERVER_FORWARD_HEADERS_STRATEGY=framework`를 추가하고 재배포한다.
- `/v3/api-docs`의 `servers[0].url`이 `https://<실제 운영 호스트>`인지 확인한다.
  Swagger의 Servers와 Execute 후 Request URL도 HTTPS여야 한다. 강력 새로고침 후 다시 확인한다.
- HTTPS 문서에서 HTTP API 요청을 만들면 브라우저가 Mixed Content로 차단할 수 있다.
  개발자 도구 Console/Network에서 실제 Request URL과 차단 사유를 확인한다.
  이 경우 `Access-Control-Allow-Origin: *` 추가나 JWT 설정 변경으로 해결하지 않는다.
- HTTP 응답 401은 Bearer 토큰 누락/만료/검증 문제다. 403 `Invalid CORS request`는
  Origin 판정 문제다. 응답 없이 `Failed to fetch`가 발생하면 Mixed Content, TLS, 네트워크,
  브라우저 CORS 차단을 실제 URL과 함께 구분한다.
- 같은 HTTPS Swagger origin에서 `/api/`를 호출하는 데는 별도 Origin 허용을 추가할 필요가 없다.
  별도 프론트 앱은 **실제 주소창의 origin**(프로토콜·호스트·포트)을 확인하고
  `APP_CORS_ALLOWED_ORIGINS`에 필요한 정확한 주소만 쉼표로 설정한다. 경로나 끝 슬래시는 넣지 않는다.
  이 값은 기존 기본 목록을 대체한다. 현재 팀 프론트의 실제 origin은 배포 담당자가 확인해야 한다.
- 기존 CORS의 GET/POST/PUT/PATCH/DELETE/OPTIONS 및 Authorization/Content-Type 허용은 유지한다.
  Google PKCE 로그인·세션 갱신·수동 Authorize 및 ES256 JWT 정책도 변경하지 않는다.

프록시 회귀 테스트는 실제 임베디드 HTTP 서버에서 forwarded 헤더를 넣어 OpenAPI URL,
동일 origin의 인증 요청, 허용/비허용 origin Preflight와 로컬 HTTP를 검증한다.
배포 후에는 일반 브라우저에서 Google 로그인 → `GET /api/auth/me` Execute → 200을 최종 확인한다.
인증 없이 HTTPS로 API를 열면 401이 정상이며, 테스트를 위해 Origin을 전부 허용하지 않는다.

## DB 스키마 운영

인증·닉네임 공통 오류와 레거시 API 전환 기준은 [슬라이스 19 안내](docs/슬라이스19_인증_닉네임_리팩터링.md)를 참고한다.

과목별 오답 목록과 재풀이 연동 기준은 [슬라이스 18 안내](docs/슬라이스18_오답노트.md)를 참고한다.

세션 요약과 KST 정답률 그래프 조회는 [슬라이스 17 안내](docs/슬라이스17_학습_리포트.md)를 참고한다.
AI 질문 SSE와 하루 20회 제한, fetch/curl 검증은 [슬라이스 16 안내](docs/슬라이스16_AI_질문.md)를 참고한다.

단원별 진도·정답률 및 문제 해설 조회 API는 [슬라이스 11 안내](docs/슬라이스11_학습_조회.md)를 참고한다.

자료 자동 만료·Storage 정리 설정과 V5 배포 순서는 [슬라이스 10 운영 안내](docs/슬라이스10_자료_정리.md)를 참고한다.
공유 DB를 사용하는 로컬에서는 `MATERIAL_CLEANUP_ENABLED=false`를 유지한다(기본값).

공용 Supabase DB를 2명이 함께 쓰므로, 스키마 변경은 **Flyway 마이그레이션 파일로만** 한다.

- `spring.jpa.hibernate.ddl-auto` 는 `validate` 로 고정한다. Hibernate는 엔티티와 실제 스키마가 일치하는지만 검증하고, 스키마를 직접 바꾸지 않는다.
- 스키마를 바꿔야 하면 `src/main/resources/db/migration/` 에 `V{n}__설명.sql` 파일을 새로 추가한다 (예: `V2__add_push_subscription.sql`). **이미 머지된 `V{n}` 파일은 절대 수정하지 않는다** — 체크섬이 달라지면 Flyway가 기동을 거부한다.
- 변경은 PR로 리뷰 후 `develop` 에 머지한다. 이후 누구든 먼저 로컬에서 앱을 기동하거나 Railway가 재배포되는 순간 자동으로 Supabase에 적용된다 (Flyway가 `flyway_schema_history` 테이블로 적용 여부를 추적하므로 중복 적용되지 않는다).
- 초기 스키마는 `V1__init_schema.sql` (이슈 #3, 백엔드 개발 명세서 6장 기준)이다.

## 개발용 시드 데이터

자료 업로드·단원화 기능이 끝나기 전에 단원·문제 API를 개발할 수 있도록 `scripts/dev_seed.sql`을 제공한다 (이슈 #4).

- **Flyway 마이그레이션 대상이 아니다.** `src/main/resources/db/migration/`에 두지 않고 `scripts/`에 따로 둔다. 운영 데모 직전에는 `dev_seed_cleanup.sql`로 지운다.
- `app_user`, `subject`, `category`, `question` 등이 모두 `user_id`로 소유권이 걸려 있어서, **각자 자기 Supabase Auth UUID로 각자 실행해야** 한다. 한 사람이 넣은 시드를 다른 사람이 그대로 조회할 수 없다.

### 실행 방법

1. 본인 구글 계정으로 한 번 로그인해서 `auth.users`에 UUID가 있는지 확인한다 (Supabase 대시보드 > Authentication > Users).
2. `scripts/dev_seed.sql`을 열어 `v_user_id` 값을 본인 UUID로 바꾼다.
3. Supabase 대시보드 > SQL Editor에 전체 내용을 붙여넣고 실행한다.
4. 과목 1개(`컴퓨터 구조`), 단원 3개, 단원별 O/X·객관식·단답형 문제(해설·출처 쪽수 포함), 학습 세션 1건과 응답 3건이 생성된다.
5. 다시 실행해도 안전하다 (이미 들어간 데이터는 건너뛴다).

### 삭제 방법

`scripts/dev_seed_cleanup.sql`을 열어 같은 `v_user_id` 값으로 바꾼 뒤 SQL Editor에서 실행한다. 시드로 넣은 `subject`와 그 하위 데이터(`material`, `category`, `question`, `session`, `session_question`, `attempt`)만 지우고, `app_user` 행(실제 로그인 계정)은 지우지 않는다.
