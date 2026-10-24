# board-game

여러 게임을 올릴 실시간 멀티플레이 서버. Kotlin 2.3 / Spring Boot 4.1 / Java 25 /
PostgreSQL + Redis / 단일 Gradle 모듈.

설계 결정의 배경은 `docs/decisions/README.md` 에 있다. 이 문서는 어기면 실제로 깨지는 것만 담는다.

## 명령어

```bash
./gradlew build                          # 전체 빌드
./gradlew test --tests '*NicknameTest'   # 하나만
./gradlew unitTest                       # 컨테이너 없이 단위 테스트만 (*IntegrationTest 제외)
./gradlew bootRun                        # 로컬 서버 :8080 (compose 자동 기동, DevTools 자동 재시작)
./gradlew bootRun --args='--spring.profiles.active=qa'   # QA 서버 :8081 (별도 DB, 덱 고정 API)
```

qa 프로필은 `app_environment` 표식이 `qa` 인 DB 에서만 기동한다(`QaProfileGuard`). 표식은 compose-qa 초기화 SQL 이 만든다.
`GET /api/server-info` 는 인증 없이 열려 있고 가드를 통과한 QA 서버만 `environment=qa` 를 돌려준다. qa 프로필에서만 QA 덱 고정 API(`POST /api/admin/qa/holdem/tables/{tableId}/next-deck`, ADMIN)가 켜진다.

Docker 가 떠 있어야 한다. 접속 정보는 `application.yaml` 에 적지 않는다 — Compose 지원이 연결한다.

`APP_JWT_SECRET`(32바이트 이상)이 없으면 기동하지 않는다. `bootRun`·`test` 는 `build.gradle.kts` 가 개발용 키를 넣어준다 — IDE 에서 메인 클래스를 직접 실행하면 실행 구성의 환경 변수에 넣는다.

## 구조

```
com.jsm.boardgame
├── common/          config/ error/ web/ persistence/
│                    error 는 domain 이 import 하는 유일한 common 패키지. web 은 절대 아니다
└── <context>/       ← 모든 바운디드 컨텍스트가 이 형태
    ├── domain/      model/ repository/ service/ exception/
    │                repository·service = 출력 포트. service 는 도메인 규칙이 필요로 하는 것(해싱, 셔플)
    ├── application/ command/{usecase,service}  port/  event/  exception/  query/{service,port,view}
    │                port = 유스케이스가 필요로 하는 출력 포트(세션, 토큰, 타 컨텍스트)
    ├── infrastructure/ persistence/{entity,adapter}  security/{adapter,config}  acl/
    │                acl = 타 컨텍스트를 부르는 어댑터. DB 를 안 건드리므로 persistence 가 아니다
    └── presentation/ config/ exception/ rest/{request,response}  ws/
```

- **한 패키지에 역할이 섞이면 가른다. 크기는 기준이 아니다.** 파일 1개짜리 하위 패키지도 그대로 둔다
- `domain/model` 처럼 패키지명이 이미 역할인 곳은 더 가르지 않는다
- `infrastructure/` 의 위 셋은 최소 형태다. DB·보안·타 컨텍스트가 아닌 기술 어댑터는 역할 이름으로 하위 패키지를 만든다(holdem: `timer/` `executor/` `queue/` `recovery/`)
- 계층 이름은 `domain`/`application`/`infrastructure`/`presentation`. `interfaces` 는 쓰지 않는다
- 새 게임은 최상위에 자기 컨텍스트를 갖는다(`com.jsm.boardgame.<game>`). `game/` 같은 상위 묶음 금지

### 의존성 방향

```
presentation ──▶ application ──▶ domain ◀── infrastructure
                      ▲                         │
                      └─────────────────────────┘
```

- `domain` 은 `common/error` 외에 아무것도 의존하지 않는다
- **`application`·`domain` 은 `infrastructure` 를 참조하지 않는다. 예외 없다**
- `infrastructure` 는 `domain` 과 `application` 을 둘 다 참조한다 — 어댑터가 자기가 구현하는
  포트를 import 하는 것이라 정상이다. 금지는 반대 방향 하나뿐이다
- 컨텍스트끼리 서로의 `domain`·`infrastructure` 를 참조하지 않는다.
  필요하면 부르는 쪽이 포트를 소유하고 `infrastructure/acl/` 의 어댑터가 상대의
  공개 `application` 만 부른다(`wallet/application/port/UserExistence`).
  교차 import 가 늘어나면 경계를 다시 봐야 한다는 신호다

## 바운디드 컨텍스트

| 컨텍스트 | 상태 | 책임 |
|---|---|---|
| `user` | 구현됨 | 사용자, 인증, 세션, 역할, 계정 잠금 |
| `wallet` | 구현됨 | 잔액·원장, 충전/환전 요청, 관리자 승인·반려·조정 |
| `holdem` | 구현됨 | 텍사스 홀덤 캐시게임 |

`user` 가 참조 구현이다. 새 컨텍스트는 그 파일 배치를 그대로 따른다.

컨텍스트 전용 규칙은 `.claude/rules/<context>.md` 에 있고 그 컨텍스트 파일을 건드릴 때만 로드된다(`holdem`·`wallet`·`user`).

주제별 규칙은 `.claude/rules/` 의 `testing`·`websocket`·`errors`·`persistence`·`transactions`·`domain-model` 에 있고, 해당 경로 파일을 읽거나 고칠 때만 로드된다.

## 규칙

1. 게임 간 공유는 없다. `GameRule`/`Move`/`GameState` 같은 게임 공통 상위 타입을 만들지 않는다.
   게임 컨텍스트끼리 import 하지 않는다. `common` 에 `Player`·`Money` 같은 도메인 개념을 넣지 않는다.
   다형성이 필요하면 application 계층의 전략 패턴으로. 같은 이름의 클래스가 두 게임에 있는 것은 중복이 아니다.
   `wallet`·`user` 는 게임이 아니므로 이 규칙의 대상이 아니다 — 단 게임의 `domain` 이 `wallet` 의
   `domain` 을 참조하면 위반이다(응용 계층에서만 부른다)
2. 도메인 모델과 JPA 엔티티는 항상 분리한다. `domain/` 아래에서 `jakarta.persistence.*`,
   `org.springframework.*` import 금지. `infrastructure/persistence/entity/` 에 `XxxJpaEntity` 와 확장 함수 매퍼
3. 명령과 조회의 경로가 다르다. 기준은 "상태를 바꾸는가" 하나

   | | 명령 | 조회 |
   |---|---|---|
   | 도메인 | 반드시 애그리거트를 거친다 | 거치지 않는다 |
   | 입력 포트 | UseCase 인터페이스를 둔다 | 두지 않는다. 서비스 클래스 하나 |
   | 출력 포트 | `domain/repository` | `application/query/port` |
   | 쿼리 | Spring Data 파생 쿼리 | Kotlin JDSL 생성자 프로젝션 |

   조회가 `JpaEntity → 도메인 → DTO` 로 두 번 매핑되면 안 된다. JDSL 은 조회 경로에만 쓴다.
   명령이 토큰처럼 값을 돌려줄 때도 출력 포트 타입을 그대로 돌려주지 않는다(`AuthTokens` vs `IssuedTokens`)
4. UseCase 이름은 의도를 드러낸다. 유스케이스당 하나. `UserService`/`UserServiceImpl` 같은 별칭 인터페이스 금지
5. 무작위성은 주입받는다. 각 게임이 자기 `Shuffler`/`DiceRoller` 포트를 정의한다.
   시계는 `common/config` 의 `Clock` 빈 하나를 공유한다 — `Instant.now()` 가 한 곳만 남아도 TTL 테스트가 흔들린다
6. 히든 정보는 뷰어별로 마스킹한다. WebSocket 으로 도메인 객체나 전체 게임 상태를 내보내지 않는다.
   공개 뷰와 개인 뷰를 별도 타입으로 조립한다. 브로드캐스트의 기본값은 뷰어별로 다른 페이로드다
   (STOMP 세부는 `.claude/rules/websocket.md`)
7. 재화는 원시 타입으로 다루지 않는다. 각 게임이 자기 VO 로 갖고, 게임 간에 공유하지 않는다
8. 오류는 코드로 계약하고, 문구는 클라이언트가 만든다
   세부(예외 소유 계층, 에러 코드 enum, 로그 수준, 시큐리티 401·403)는 `.claude/rules/errors.md`

## 주석

- **KDoc 안에 `/api/admin` 와일드카드를 그대로 쓰지 않는다.** 별표-슬래시가 주석을 닫아
  `Unclosed comment` 가 엉뚱한 줄을 가리킨다

## git flow

`master`(배포·태그) ← `release/*` ← `develop` ← `feature/*`. `hotfix/*` 는 `master` 에서 분기해 양쪽에 병합.

- `master`·`develop` 에 직접 커밋하지 않는다
- **첫 편집 전에 `feature/` 브랜치부터 만든다** — 문서 한 줄도 예외 없다. 커밋할 때 만드는 건 위반이다
- 작업 브랜치는 **항상 `feature/` 로 시작한다** — 버그 수정·리팩터링·테스트·문서 작업도 예외 없다(`fix/`·`chore/`·`test/`·`docs/` 브랜치를 만들지 않는다). 그 뒤에 컨텍스트 접두를 붙인다(`feature/user-signup`, `feature/holdem-view-seq`)
- 커밋은 Conventional Commits + 컨텍스트 스코프(`fix(holdem): ...`) — 작업 성격은 브랜치가 아니라 커밋 타입으로 드러낸다
- `feature/*` → `develop` 은 `git merge --no-ff` 로 합친다
- 충돌이 나면 `develop` 에서 해결하지 않는다 — feature 브랜치에 `develop` 을 먼저 합쳐 거기서 풀고 테스트한 뒤 병합한다
