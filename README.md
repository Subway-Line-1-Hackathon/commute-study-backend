# commute-study-api

Spring Boot 4.1.2 + PostgreSQL(Supabase) 기반 API 서버. Railway 로 배포한다.

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

| 변수명 | 설명 |
|---|---|
| `SPRING_DATASOURCE_URL` | Supabase JDBC URL (6543, Transaction pooling) |
| `SPRING_DATASOURCE_USERNAME` | Supabase DB 사용자명 |
| `SPRING_DATASOURCE_PASSWORD` | Supabase DB 비밀번호 |
| `SPRING_FLYWAY_URL` | Supabase JDBC URL (5432, Session/Direct) |
| `SPRING_FLYWAY_USER` | Supabase DB 사용자명 (datasource와 동일) |
| `SPRING_FLYWAY_PASSWORD` | Supabase DB 비밀번호 (datasource와 동일) |

Railway는 자체적으로 `PORT` 환경변수를 주입하며, `application.yaml` 의 `server.port: ${PORT:8080}` 설정이 이를 자동으로 반영한다. 별도 설정 불필요.

빌드는 Railway의 기본 Gradle 빌드(`./gradlew bootJar`)를 사용하면 된다.

Railway가 자동 생성하는 Start Command는 멀티모듈 구조를 가정한 와일드카드(`*/build/libs/*jar`)를 쓰는데, 이 레포는 단일 모듈이라 경로가 맞지 않아 컨테이너 기동 시 `ls: cannot access` 에러로 실패한다. **Settings > Deploy > Custom Start Command**에 아래를 직접 지정해야 한다.

```
java -jar build/libs/commute-study-api-0.0.1-SNAPSHOT.jar
```

## DB 스키마 운영

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
