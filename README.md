# commute-study-api

Spring Boot 3 + PostgreSQL(Supabase) 기반 API 서버. Railway 로 배포한다.

## 요구 사항

- JDK 21
- Supabase 프로젝트 (DB 공용으로 팀원과 공유)

## 로컬 실행

1. `src/main/resources/application-local.yaml.example` 을 같은 폴더에 `application-local.yaml` 로 복사한다.
   (이 파일은 `.gitignore` 에 등록되어 깃에 올라가지 않는다.)
2. Supabase 대시보드 > **Project Settings > Database > Connection string** 에서 아래 값을 확인해 `application-local.yaml` 에 채운다.
   - 접속 주소는 **Connection pooling (Transaction mode, 6543 포트)** 사용을 권장한다. (서버리스/다중 인스턴스 환경에서 커넥션 수 제한 문제를 피할 수 있음)
   - DB 비밀번호는 깃/슬랙 등 공개 채널이 아닌 팀원과의 별도 채널(1:1 DM 등)로 공유한다.
3. 실행

   ```bash
   ./gradlew bootRun
   ```

## 배포 (Railway)

Railway 프로젝트의 **Variables** 탭에 아래 환경변수를 등록한다. (Supabase 접속 정보와 동일, URL에 `?sslmode=require` 포함)

| 변수명 | 설명 |
|---|---|
| `SPRING_DATASOURCE_URL` | Supabase JDBC URL |
| `SPRING_DATASOURCE_USERNAME` | Supabase DB 사용자명 |
| `SPRING_DATASOURCE_PASSWORD` | Supabase DB 비밀번호 |

Railway는 자체적으로 `PORT` 환경변수를 주입하며, `application.yaml` 의 `server.port: ${PORT:8080}` 설정이 이를 자동으로 반영한다. 별도 설정 불필요.

빌드는 Railway의 기본 Gradle 빌드(`./gradlew bootJar`)를 사용하면 된다.
