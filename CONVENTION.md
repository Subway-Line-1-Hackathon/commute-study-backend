# Git Convention

팀 프로젝트의 일관된 협업을 위한 Git 사용 규칙입니다. 모든 팀원은 아래 규칙을 따릅니다.

## 목차

1. [브랜치 전략](#1-브랜치-전략)
2. [브랜치 네이밍](#2-브랜치-네이밍)
3. [이슈 규칙](#3-이슈-규칙)
4. [커밋 메시지 규칙](#4-커밋-메시지-규칙)
5. [Pull Request 규칙](#5-pull-request-규칙)
6. [머지 규칙](#6-머지-규칙)
7. [작업 흐름 요약](#7-작업-흐름-요약)
8. [주의사항](#8-주의사항)

---

## 1. 브랜치 전략

| 브랜치 | 용도 | 비고 |
| --- | --- | --- |
| `develop` | 기본(Default) 브랜치. 개발 내용이 모이는 통합 브랜치 | 직접 push 금지, PR로만 병합 |
| `{Type}/#{이슈번호}` | 기능/수정/문서 등 개별 작업 브랜치 | `develop`에서 분기 |

- 모든 작업은 **이슈를 먼저 생성**한 뒤, 해당 이슈 번호로 브랜치를 만듭니다.
- 작업 브랜치는 항상 최신 `develop`에서 분기합니다.
- 작업이 끝난 브랜치는 PR 머지 후 삭제합니다.

---

## 2. 브랜치 네이밍

### 형식

```
{Type}/#{이슈번호}
```

### 예시

```
Feat/#9
Chore/#2
Docs/#3
Fix/#21
```

### Type 종류

| Type | 설명 |
| --- | --- |
| `Feat` | 새로운 기능 추가 |
| `Fix` | 버그 수정 |
| `Docs` | 문서 추가/수정 (README, ERD 등) |
| `Chore` | 빌드 설정, 환경 설정, 패키지 관리 등 기타 작업 |
| `Refactor` | 기능 변경 없는 코드 구조 개선 |
| `Test` | 테스트 코드 추가/수정 |
| `Style` | 코드 포맷팅, 세미콜론 누락 등 (로직 변경 없음) |
| `Hotfix` | 긴급 버그 수정 |

> Type의 첫 글자는 **대문자**로 작성합니다.

### 브랜치 생성 예시

```bash
git checkout develop
git pull origin develop
git checkout -b Feat/#9
```

---

## 3. 이슈 규칙

- 작업 시작 전 반드시 이슈를 생성합니다.
- 이슈 제목은 커밋 메시지와 같은 Type 규칙을 따릅니다.
- 이슈 번호가 곧 브랜치 번호가 됩니다. (이슈 `#9` → 브랜치 `Feat/#9`)

### 이슈 제목 예시

```
[Feat] 오래된 요청 로그 정리 스케줄러 추가
[Chore] 로컬 PostGIS docker-compose 및 환경변수 설정
[Docs] ERD Cloud로 시각화할 ERD 작성
```

### 이슈 내용 권장 항목

- **작업 개요**: 무엇을 왜 하는지
- **작업 상세**: 해야 할 일 체크리스트
- **참고 사항**: 관련 링크, 논의 내용 등

---

## 4. 커밋 메시지 규칙

### 형식

```
[{Type}/#{이슈번호}] {Type}: {작업 내용}
```

- `{Type}`은 브랜치 Type과 동일하게 작성합니다.
- `{작업 내용}`은 **한글**로, 무엇을 했는지 간결하게 작성합니다.
- 마침표(`.`)는 붙이지 않습니다.
- 제목은 50자 내외로 작성합니다.

### 예시

```
[Feat/#9] Feat: 오래된 요청 로그 정리 스케줄러 추가
[Feat/#9] Feat: 추천 응답 캐시(Caffeine) 추가 및 RouteService 수정
[Chore/#2] Chore: 로컬 PostGIS docker-compose 및 환경변수 설정
[Chore/#1] Feat: 스프링 프로젝트 초기 설정
[Docs/#3] Docs: dev_seed 실행 및 삭제 방법 README 추가
[Docs] Docs: ERD Cloud로 시각화할 ERD 작성
```

### 프로젝트 최초 세팅 커밋

이슈 없이 진행하는 최초 초기화 작업에만 아래 형식을 사용합니다.

```
[init]: 깃허브 이슈 생성 스크립트 추가
```

### 커밋 단위

- 하나의 커밋에는 **하나의 논리적 변경**만 담습니다.
- 여러 기능을 한 커밋에 섞지 않습니다.
- 빌드가 깨진 상태로 커밋하지 않습니다.

### 본문이 필요한 경우 (선택)

제목과 본문 사이에는 빈 줄을 두고, **무엇을/왜** 변경했는지 작성합니다.

```
[Feat/#9] Feat: 추천 응답 캐시(Caffeine) 추가 및 RouteService 수정

- 동일 조건의 추천 요청에 대해 응답을 캐싱하여 응답 속도 개선
- 캐시 TTL 10분, 최대 크기 1000으로 설정
```

---

## 5. Pull Request 규칙

### PR 제목

```
[{Type}/#{이슈번호}] {Type}: {작업 내용}
```

예시:

```
[Feat/#9] Feat: 오래된 요청 로그 정리 스케줄러 추가
```

### PR 규칙

- `base` 브랜치는 반드시 `develop`으로 설정합니다.
- 본문에 연결 이슈를 명시합니다. (`Closes #9`)
- 최소 **1명 이상의 리뷰어 승인(Approve)** 후 머지합니다.
- 리뷰 코멘트는 모두 해결(Resolve)한 뒤 머지합니다.
- PR은 가급적 작게, 하나의 이슈 단위로 올립니다.
- 머지 전 `develop`과 충돌이 없는지 확인합니다.

### PR 본문 템플릿

```markdown
## 요약
-
## 변경 사항
- 

## 테스트
- [ ] 로컬에서 빌드/실행 확인
- [ ] 테스트 통과
- [ ] 불필요한 코드/주석 제거

## 참고
- Closes #4
```

---

## 6. 머지 규칙

- 머지 방식은 **Merge commit**을 사용합니다.
    - 머지 커밋 메시지 예: `Merge pull request #20 from {조직}/Feat/#9`
- 머지는 **PR 작성자 본인** 또는 합의된 담당자가 진행합니다.
- 머지 후 작업 브랜치는 삭제합니다.
- 머지 후 팀원들은 `develop`을 pull 하여 최신 상태를 유지합니다.

---

## 7. 작업 흐름 요약

```
1. 이슈 생성                      → 이슈 #9 생성
2. develop 최신화                 → git pull origin develop
3. 작업 브랜치 생성               → git checkout -b Feat/#9
4. 작업 및 커밋                   → [Feat/#9] Feat: ...
5. 원격 push                      → git push origin Feat/#9
6. PR 생성 (base: develop)        → [Feat/#9] Feat: ...
7. 코드 리뷰 및 Approve
8. develop으로 머지
9. 작업 브랜치 삭제
```

### 명령어 예시

```bash
# 1. 최신 develop 받기
git checkout develop
git pull origin develop

# 2. 작업 브랜치 생성
git checkout -b Feat/#9

# 3. 작업 후 커밋
git add .
git commit -m "[Feat/#9] Feat: 오래된 요청 로그 정리 스케줄러 추가"

# 4. 원격에 push
git push origin Feat/#9
```

### 브랜치가 develop보다 뒤처진 경우 (Behind)

```bash
git checkout develop
git pull origin develop
git checkout Feat/#9
git merge develop    # 또는 git rebase develop
```

---

## 8. 주의사항

- `develop` 브랜치에 **직접 push 하지 않습니다.**
- `git push --force`는 **사용하지 않습니다.** (불가피한 경우 팀원과 사전 합의)
- 비밀번호, API Key, 토큰 등은 **절대 커밋하지 않습니다.** (`.env`는 `.gitignore`에 등록, 예시는 `.env.example`로 공유)
- 환경 설정 파일을 수정했다면 PR 본문에 변경 사항을 명시합니다.
- 커밋 전 `git status`, `git diff`로 의도치 않은 파일이 포함되지 않았는지 확인합니다.
- 오래된 브랜치는 정기적으로 정리합니다.
