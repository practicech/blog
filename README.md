# 팀 블로그 플랫폼 — 소스 저장소

여러 사람이 각자 블로그를 갖고 글을 쓰는 서비스입니다(티스토리·벨로그 같은 형태). 운영 사이트: https://geulgreen.java21.net
기능은 [GitHub Spec Kit](https://github.com/github/spec-kit)으로 명세를 먼저 쓰고 그대로 구현했으며, 명세 001~024(팀 공통 최소 요구사항, v1.0.0)가 모두 구현돼 있습니다.
**명세 025부터는 강성찬 개인 확장**입니다(팀은 공통만 맞추고 각자 추가만 하기로 함). 변경 내역은 [`CHANGELOG.md`](CHANGELOG.md).

> 이 저장소는 **소스 코드·빌드·배포**만 담습니다. 설계 문서(`docs/`)와 기능 명세(`specs/`)는 문서 저장소 **[AIPKANG/docs](https://github.com/AIPKANG/docs)** 에 있습니다.

## 할 수 있는 것

### 팀 공통 최소 요구사항 (v1.0.0, 명세 001~024)

| 영역 | 기능 (명세 번호) |
|---|---|
| 회원 | 이메일 가입·인증, Google·GitHub 로그인(001), 블로그 주소·닉네임(002), 프로필·설정(003), 탈퇴·30일 안 복구(023) |
| 글쓰기 | 자동 저장·충돌 비교(004), 발행·다시 발행(005), 공개/비공개(006), Markdown 정화(007), 사진 올리기(008), 태그(013), AI 태그 추천(021) |
| 읽기 | 전체 글·개인 블로그(009), 글 상세(010), 내 글 관리·휴지통(011), 트렌딩(019), 검색(020), 다크 모드(024) |
| 반응 | 댓글·답글(014), 좋아요(015), 조회수(016), 팔로우·피드(018), 인앱 알림(017) |
| 운영 | 접근 권한 공통 규칙(012), 신고·관리자 숨김·회원 정지(022), 개인정보 처리방침(`/privacy`), sitemap·robots |

### 강성찬 개인 확장 (명세 025~071)

팀은 공통만 맞추고 각자 **추가만** 하기로 했다(문서 저장소 [docs/01 §2-4](https://github.com/AIPKANG/docs/blob/main/docs/01-common-requirements.md)). 아래는 강성찬 개인 확장이며 공통 완료 기준은 그대로 통과한다.

| 명세 | 기능 | 끄는 설정 |
|---|---|---|
| 025 | 친구 공개 — 친구만 보는 글(친구 맺는 방식은 069) | — |
| 026 | 공개 전환 — 공개로 넓힐 때 확인, 친구에게 첫 공개 응원 알림 | — |
| 027 | 조회수 30분 5회 | `blog.view.*` |
| 028 | 답글 무제한 깊이(3단계 뒤 접기) | `blog.comment.max-depth: 1` |
| 029 | 링크 공개 — 주소의 열쇠를 아는 사람만 | `blog.share.link.enabled` |
| 030 | 그룹 공개 — 친구 그룹에만 보이는 글 | `blog.friend.groups.enabled` |
| 031 | 잔디·스트릭 — 블로그 1년 기록 칸 | `blog.activity.enabled` |
| 032 | 작성자 통계 — `/manage/stats` | `blog.stats.enabled` |
| 033 | 짧은 기록 — 280자 한 줄 기록 | `blog.notes.enabled` |
| 034 | ~~같은 주제로 쓰기·릴레이~~ → 057로 바뀜 | — |
| 035 | AI 티저 — 카드 한 줄 소개 | `blog.teaser.enabled` |
| 036 | 홈 "새로 온 작가" | `blog.home.newcomers.enabled` |
| 037 | 카테고리 — 블로그 글 분류, `/settings/categories` | `blog.categories.enabled` |
| 038 | 이전 글·다음 글 + 관련 글 | `blog.reading.navigation-enabled` |
| 039 | 목차 자동 생성(소제목 2개 이상) | `blog.reading.toc-enabled` |
| 040 | 방명록 — `/@주소/guestbook`, 비밀글 | `blog.guestbook.enabled` |
| 041 | 시리즈 — 글 묶기, 이전편·다음편 | `blog.series.enabled` |
| 042 | 예약 발행 — 정한 시각에 발행 | `blog.schedule.enabled` |
| 043 | 비밀 댓글 — 글쓴이와 쓴 사람만 | `blog.comment.secret-enabled` |
| 044 | RSS 구독 — `/rss`, `/@주소/rss` | `blog.rss.enabled` |
| 045~050 | 관리자 화면 확장 — `/admin` 대시보드, 회원(이메일 검색), 글·댓글 바로 숨기기, 방명록, 사이트 공지, 관리 기록 | — |
| 050 | 관리자 지정 — 이 이메일로 가입·인증하면 관리자 | `blog.admin.emails`(서버 `ADMIN_EMAILS`) |
| 051 | 블로그 방문자 수(오늘·전체) | `blog.visits.enabled` |
| 052 | 읽기 진행 막대 | `blog.reading.progress-enabled` |
| 053 | 공유하기(링크 복사·X·페이스북·다른 앱) | `blog.reading.share-enabled` |
| 054 | 글 고정(블로그 맨 위 한 글) | — |
| 055 | 카드 대표 사진 — 자동/본문 사진/새로 올리기 | `blog.cover.enabled` |
| 056 | 태그 화면 리뉴얼·태그 아이콘(내장 규칙 + AI 다듬기) | `blog.tag-icons.enabled` |
| 057 | 오늘의 미션 — 매일 주제 3개(AI 또는 내장 120개), 고르면 글쓰기, 발행하면 완료, 친구 글·알림, 완료 횟수·연속 일수 | `blog.missions.enabled` |
| 058 | 글쓰기 서식 창 — 노션처럼 글을 고르면 창이 뜸: 서식 종류(본문·제목·목록·인용·코드 블록), 글자 색·배경색, 굵게·기울임·밑줄·취소선·코드·링크, 서식 지우기, 단축키(저장은 그대로 Markdown) | — |
| 059 | AI로 글 다듬기 — 서식 창에서 고른 글을 다듬기·맞춤법·짧게·쉽게·직접 부탁, [바꾸기]·[아래에 넣기](Gemini 키가 있을 때) | `blog.writing-ai.enabled` |
| 060 | 보이는 대로 쓰기 — 본문이 글 화면 모양 그대로 보이고 서식이 바로 적용됨, # - 1. > 단축 입력, [Markdown으로 쓰기] 전환(저장은 그대로 Markdown) | — |
| 061 | 화면 테마를 개인 설정으로 — 머리말 다크 모드 버튼 대신 설정 → 화면 테마(시스템·라이트·다크) | — |
| 062 | 빈 임시글이 안 생기게 — 안 쓰고 떠나면 바로 지움, [새 글]은 남은 빈 글을 다시 엶, 내 글 관리에서 빈 임시글을 뺌 | — |
| 064 | 글쓰기 / 명령 — 줄 처음에 `/`를 쓰면 제목·목록·인용·코드·표·구분선·사진을 골라 넣음(두 칸 모두) | — |
| 065 | 표 넣기 — 글쓰기 [표] 버튼·`/표`, 칸 사이 Tab·Enter 이동(저장은 GFM 표) | — |
| 066 | 미션 배지 — 완료한 날 수·최고 연속으로 정하는 배지 7개, 미션 화면에서 본인만 | `blog.missions.enabled` |
| 067 | 화면 정리 — 내 글·시리즈 글을 홈과 같은 카드로(⋯ 관리 메뉴·상태 배지), 홈 탭 자리 고정·트렌딩 뒤 최신 글 이어 붙이기·오른쪽 칸 고정과 추천 블로그, 목록 무한 스크롤, 새로 온 작가 가로 한 줄, 머리말·꼬리말 정리(좁은 화면 ☰에 약관·릴리스 노트), 첫 배너 WebP·사진 1년 보관 | — |
| 068 | 기능 다듬기 — 알림 창을 열면 모두 읽음, 피드를 추천 블로그·팔로잉 블로그 글로 나눔, AI 태그 추천 최소 20자, [발행] 설정을 팝업으로, 글쓰기 사진·표 버튼은 위쪽 머리줄 | `blog.ai.tag-suggest.min-chars` |
| 069 | 친한 친구 — 내가 팔로우하는 사람 중에서 골라 넣으면(수락·알림 없음) 그 사람이 내 친구 공개 글을 봄, 언팔하면 친구·그룹에서도 빠짐 | — |
| 070 | 가입 화면 덜어내기 — 로그인과 같은 그림 카드, 비밀번호 규칙은 칸을 누르면 보임, 주소 안내 한 줄, 약관 "모두 동의" | — |
| 071 | 릴리스 노트 화면 — `/releases`가 `CHANGELOG.md`를 보여 줌, 꼬리말에 링크 | — |
| 072 | 비밀 대댓글·공유하기 접기 — 보통 댓글에도 비밀 답글(글쓴이·원댓글 쓴 사람·나만), 공유는 📤 버튼 하나의 메뉴로 | — |
| 073 | 비공개 계정 — 켜면 팔로우가 요청이 되고, 수락한 팔로워만 글·방명록·시리즈 등을 봄 | `blog.private-accounts.enabled` |
| 074 | 인스타식 계정 — 이메일 6자리 코드로 가입, 이메일·아이디로 로그인, 코드로 비밀번호 찾기, 블로그 주소·닉네임은 14일에 두 번 바꾸고 옛 주소는 새 주소로 넘김, 블로그 머리말 숫자 줄 | `blog.insta-account.enabled` |
| 075 | 글 화면 정리 — 글쓴이 관리(수정·공개 범위·삭제)는 ⋯ 메뉴, 고정·카테고리·시리즈는 글 설정 창, 닉네임 옆 @아이디는 블로그 첫 화면 프로필 줄에만 | — |
| 076 | 블로그 주소에서 @ 빼기 — `/{주소}`로 열고 예전 `/@` 주소는 301로 넘김(예약어와 겹치는 기존 주소는 `/@` 유지) | — |
| 077 | PC 홈 꼬리말 한 번만(프로필 메뉴 안 꼬리말 링크도 숨김), 오른쪽 칸 추천 글 | — |
| 078 | 검색창 추천 태그 — 머리말 🔍를 누르면 검색창과 추천 태그 10개(머리말 "태그" 메뉴 대신) | — |
| 079 | 탐색 — 위 메뉴를 홈 · 탐색 · 미션 · 피드로, `/explore`에서 인기 태그·새로 온 작가·추천 글·추천 블로그 | — |
| 080 | 글 상세 velog식 — 큰 제목 아래 작성자 줄·팔로우, 태그 칩을 위로, 댓글 다음 "관심 있을 만한 글" 카드 무한 스크롤 | — |
| 081 | 피드에 추천 글 섞기 — 팔로우한 글 사이에 "추천" 표시와 이유를 단 글, 팔로우한 글을 다 보면 추천 글로 이어짐 | — |
| 082 | 아이디를 주 이름으로 — 작성자 줄·카드·댓글·알림 등은 아이디(@ 없이), 닉네임은 블로그 프로필 줄과 사람 목록에서만 작게 | — |
| 083 | 테스트용 긴 글 — 관리자 화면 [테스트 글 넣기]·[모두 지우기], 로그인 못 하는 테스트 계정 4개와 긴 글 12개 | — |
| 084 | 카드 정렬 — 제목·요약 줄 자리를 고정해 나란한 카드의 위아래를 맞춤(홈·피드·태그·검색·블로그·시리즈·내 글·미션), 피드 추천 글은 한 번만, "추천"은 작성자 줄 칩 | — |
| 085 | 글 점검 — 글쓰기 미리보기 대신 [글 점검]으로 맞춤법·서식 추천 | — |
| 086 | 피드·홈 나누기 — 피드는 팔로우한 글만, 추천·트렌딩은 탐색으로, 홈은 최신 글만, 인기 태그는 검색창과 /tags에서만, 카드 위 대표 사진 | — |
| 087 | 친구·그룹 한 화면 — 설정의 친구와 친구 그룹을 한 화면으로(예전 /settings/groups는 넘김), 비공개 계정 스위치는 설정 > 계정 | — |
| 088 | 글 카드 한 모양 — 어디서나 대표 사진 위, 아바타·아이디·태그, 제목, 요약, 좋아요·댓글·날짜(회색 하트) | — |
| 089 | 댓글·답글 좋아요 — 댓글마다 ♡와 개수, 댓글 쓴 사람에게 좋아요 알림 | `blog.comment.like-enabled` |
| 090 | 문의하기 — 사이트 안 문의 양식(/contact)과 관리자 문의함, 개인 메일 주소를 드러내지 않음 | `blog.contact.enabled` |
| 091 | 무한 스크롤은 내려갔을 때만 — 목록 끝 반 화면 안으로 내려가면 한 묶음씩, 첫 화면은 모자랄 때만 채움 | — |
| 093 | 무중단 배포 — 앱 두 대를 한 대씩 교체, 실패하면 그 대만 되돌림, 한 대당 메모리 1GB, 예약 작업은 한 대에서만 | — |
| — | 닉네임 2~30자 — 가입·소셜 가입·프로필 편집·서버 검사, 긴 닉네임은 목록·프로필·카드·댓글에서 … 로 자름 | — |

그 밖에 디자인 시안(크림·세이지, 사진 배너, 한 줄 4개 카드·한 쪽 12개)도 강성찬 개인 확장으로 적용했다. 변경 내역: [`CHANGELOG.md`](CHANGELOG.md).

## 기술 구성

- **앱**: Java 21, Spring Boot 4.1, Gradle(Kotlin DSL). 화면은 Thymeleaf 서버 렌더링 + 작은 순수 JS, 세션 로그인(Spring Session·Redis)
- **데이터**: PostgreSQL 18(스키마는 `V1__common_schema.sql` 하나, Flyway), Redis(자동 저장 버퍼·요청 제한·조회수·트렌딩·세션)
- **사진**: 처음에는 서버 디스크에 저장하고, 서버를 늘릴 때 MinIO(S3 호환)로 설정만 바꿔 확장
- **메일**: 개발 Mailpit, 운영 Gmail SMTP / **AI**: Gemini 무료 등급 → 자체 Ollama
- **구조**: 저장소를 **백엔드·프론트 두 Gradle 모듈**로 나눴습니다(1.14.0).
  - `backend/`: Spring Boot 서버. Java 코드·설정·DB 마이그레이션·테스트. 기능별 모듈로 나눈 모듈러 모놀리스(`backend/src/main/java/com/team/blog/`)
  - `frontend/`: 화면. Thymeleaf 템플릿(`templates/`)·JS·CSS·글꼴(`static/`)·브라우저 라이브러리(webjars)·화면 JS 자체 검사(`src/test/js/`)
  - `frontend`는 jar 하나로 묶여 `backend`의 실행 jar에 들어갑니다. 배포 단위는 지금처럼 앱 하나라 서버 설정은 그대로이고, 서버는 `classpath:/templates/`·`classpath:/static/`으로 화면을 읽습니다.

백엔드 기능 모듈:

| 모듈 | 담당 |
|---|---|
| `account` | 가입·로그인·프로필·탈퇴 |
| `post` | 글쓰기·발행·공개 범위·상세·내 글 관리·읽기 권한 판정 |
| `tag` | 태그, AI 태그 추천 |
| `media` | 사진 올리기·썸네일·정리 |
| `interaction` | 댓글·좋아요·팔로우 |
| `discovery` | 홈·블로그 목록·트렌딩·검색·조회수·피드 |
| `notification` | 인앱 알림 |
| `moderation` | 신고·숨김·정지·관리자 화면 |
| `shared` | 보안·오류 응답·공통 화면·사건(event) |

## 로컬에서 실행

필요한 것: JDK 21, Docker.

```bash
docker compose up -d        # PostgreSQL·Redis·Mailpit·사진 저장소(MinIO 포크)
./gradlew bootRun           # http://localhost:8080 (개발 프로필)
```

- 가입 인증 메일은 Mailpit(http://localhost:8025)에서 봅니다.
- 기본 포트(5432·6379·1025·8025·9000)가 겹치면 `DB_PORT`·`REDIS_PORT`·`MAIL_PORT`·`MAILPIT_WEB_PORT`·`STORAGE_PORT`로 바꾸고 앱에도 같은 값을 줍니다.
- 소셜 로그인·Gmail·Gemini 키는 `.env`(git에 올리지 않음)에 넣습니다. 만드는 법은 문서 저장소의 [specs/001-auth/secrets-setup.md](https://github.com/AIPKANG/docs/blob/main/specs/001-auth/secrets-setup.md).
- 관리자 화면을 보려면 DB에서 `UPDATE member SET role = 'ADMIN' WHERE handle = '내주소';` 후 머리말 [관리].

## 테스트

```bash
./gradlew test              # 통합 테스트 656개(Testcontainers로 PostgreSQL·Redis·Mailpit·MinIO를 띄움)
node frontend/src/test/js/diff.test.mjs   # 편집 비교 창 diff 자체 검사(선택)
node frontend/src/test/js/format-toolbar.test.mjs   # 글쓰기 서식 창 자체 검사(선택, 058)
```

## 배포

- 운영 모드로 전체를 띄우는 법·환경 변수·확장 순서: [`DEPLOY.md`](DEPLOY.md)
- 서버 한 대(Nginx + Let's Encrypt) 배포 순서: [`deploy/README.md`](deploy/README.md)
- 값 견본: `.env.example`

## 문서 위치

| 경로 | 내용 |
|---|---|
| [AIPKANG/docs](https://github.com/AIPKANG/docs) | 문서 저장소: 팀 원본 설계 문서(`docs/`), 기능별 명세(`specs/NNN-기능/` spec·plan·tasks), 팀 결정 기록(`specs/README.md`), 프로젝트 헌법 |
| [`CHANGELOG.md`](CHANGELOG.md) | 릴리스별 변경 내역(개인 확장은 "강성찬 개인 확장"으로 표시) |
| `backend/src/main/resources/db/migration/` | 실제 DB 스키마: V1 공통(테이블 20개), V2~V14 강성찬 개인 확장(테이블 22개 추가, 공통 테이블은 CHECK 교체와 `comment.secret` 칸 추가만) |
| `db/demo/` | 앱에 바로 넣는 한국어 데모 데이터(PostgreSQL)와 데모 계정 |
| `db/mysql/` | 같은 스키마의 MySQL 사본과 데모 데이터(ERD 도구용) |
| `deploy/school/` | 학교 공용 서버 배포(GitHub Actions → SSH), Secrets 목록 |
| Crowfoot ERD | https://crowfoot.java21.net/workspaces/62/models/672 (테이블 20개·관계 41개, 명세 001~024와 연결) |

## 작업 방식

기능마다 Spec Kit 순서로 진행합니다: `/speckit-specify` 명세 → `/speckit-clarify`(선택) → `/speckit-plan` 계획 → `/speckit-tasks` 작업 → `/speckit-implement` 구현.
명세·계획은 문서 저장소([AIPKANG/docs](https://github.com/AIPKANG/docs))에, 구현은 이 저장소에 올립니다.

브랜치는 **깃플로우**를 따릅니다(1.14.0부터).

| 브랜치 | 쓰임 |
|---|---|
| `main` | 배포된 코드만. 여기에 머지되면 테스트 후 학교 서버로 자동 배포(`.github/workflows/deploy.yml`), 릴리스마다 `vX.Y.Z` 태그 |
| `develop` | 다음 릴리스를 모으는 곳 |
| `feature/<기능>` | `develop`에서 따서 기능 하나를 만들고 PR로 `develop`에 머지 |
| `release/X.Y.Z` | 배포 직전 CHANGELOG·버전 정리, PR로 `main`에 머지 → 태그 → `develop`에도 머지 |
| `hotfix/X.Y.Z` | 운영 급한 수정. `main`에서 따서 `main`·`develop` 둘 다에 머지 |

`main`·`develop`으로 가는 PR은 테스트만 돌고, 배포는 `main`에 머지될 때만 일어납니다.
PR·이슈는 `.github`의 템플릿을 따릅니다.
