# board-game

여러 게임을 올릴 실시간 멀티플레이 서버. Kotlin 2.3 / Spring Boot 4.1 / Java 25 /
PostgreSQL + Redis / 단일 Gradle 모듈.

설계 결정의 배경은 `docs/architecture-decisions.md` 에 있다. 이 문서는 어기면 실제로 깨지는 것만 담는다.

## 명령어

```bash
./gradlew build                          # 전체 빌드
./gradlew test --tests '*NicknameTest'   # 하나만
./gradlew unitTest                       # 컨테이너 없이 단위 테스트만 (*IntegrationTest 제외)
./gradlew bootRun                        # 로컬 실행 (compose.yaml 자동 기동)
```

Docker 가 떠 있어야 한다. 접속 정보는 `application.yaml` 에 적지 않는다 — Compose 지원이 연결한다.

`APP_JWT_SECRET`(32바이트 이상)이 없으면 기동하지 않는다. `bootRun`·`test` 는 `build.gradle.kts` 가 개발용 키를 넣어준다 — IDE 에서 메인 클래스를 직접 실행하면 실행 구성의 환경 변수에 넣는다

## 테스트

- 통합 테스트에는 `@Import(TestcontainersConfiguration::class)` 가 필요하다.
  빠뜨리면 "Failed to determine a suitable driver class" 로 컨텍스트가 뜨지 않는다
- 컨테이너가 필요한 테스트만 클래스 이름에 `IntegrationTest` 접미사를 붙인다
- 목 라이브러리를 쓰지 않는다. 인메모리 페이크를 테스트 파일 안에 만든다
- **페이크 이름에 테스트별 접두를 붙인다**(`RequestFakeWalletRepository`). top-level `private`
  클래스는 파일 안에서만 보이지만 이름은 패키지를 차지해, 같은 패키지의 두 테스트가 같은
  이름을 쓰면 `Redeclaration` 이다
- 예외는 메시지가 아니라 `errorCode` 로 검증한다
- 테스트 이름은 백틱을 쓴 한국어 문장
- wallet 통합 테스트는 `UserRepository` 로 **실제 `users` 행을 먼저 만든다**. `fk_wallets_user`
  때문에 합성 userId 로 지갑을 열면 FK 위반이다. 충전·환전 *요청* 테이블에는 FK 가 없다
- 규칙을 테스트로 못 박을 때는 성격이 다른 경로를 여럿 잡는다. 이 저장소에서 세 번
  같은 식으로 뚫렸다 — 사전 체크 경로만 타서 DB 제약 번역이 한 번도 안 돌았다

## 구조

```
com.jsm.boardgame
├── common/          config/ error/ web/ persistence/
│                    error 는 domain 이 import 하는 유일한 common 패키지. web 은 절대 아니다
└── <context>/       ← 모든 바운디드 컨텍스트가 이 형태
    ├── domain/      model/ repository/ service/ exception/
    │                repository·service = 출력 포트. service 는 도메인 규칙이 필요로 하는 것(해싱, 셔플)
    ├── application/ command/{usecase,service}  port/  exception/  query/{service,port,view}
    │                port = 유스케이스가 필요로 하는 출력 포트(세션, 토큰, 타 컨텍스트)
    ├── infrastructure/ persistence/{entity,adapter}  security/{adapter,config}  acl/
    │                acl = 타 컨텍스트를 부르는 어댑터. DB 를 안 건드리므로 persistence 가 아니다
    └── presentation/ config/ exception/ rest/{request,response}  ws/(홀덤 4단계, 아직 없음)
```

- **한 패키지에 역할이 섞이면 가른다. 크기는 기준이 아니다.** 파일 1개짜리 하위 패키지도 그대로 둔다
- `domain/model` 처럼 패키지명이 이미 역할인 곳은 더 가르지 않는다
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
  교차 import 는 저장소에 이 한 곳뿐이고, 늘어나면 경계를 다시 봐야 한다는 신호다

## 바운디드 컨텍스트

| 컨텍스트 | 상태 | 책임 |
|---|---|---|
| `user` | 구현됨 | 사용자, 인증, 세션, 역할 |
| `wallet` | 구현됨 | 잔액·원장, 충전/환전 요청, 관리자 승인·반려·조정 |
| `holdem` | 구현됨 | 홀덤 규칙 전부. 방/좌석을 자기 안에 둔다 |

`user` 가 참조 구현이다. 새 컨텍스트는 그 파일 배치를 그대로 따른다.

홀덤 전용 규칙은 `.claude/rules/holdem.md` 에 있고 `holdem` 파일을 건드릴 때만 로드된다.

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
   `game.viewFor(viewer)` 를 거친다. 브로드캐스트의 기본값은 좌석별로 다른 페이로드다
7. 재화는 원시 타입으로 다루지 않는다. 각 게임이 자기 VO 로 갖고, 게임 간에 공유하지 않는다
8. 오류는 코드로 계약하고, 문구는 클라이언트가 만든다
   - 예외는 그 실패를 소유한 계층에 둔다. 기준은 "애그리거트가 그 단어를 아는가" —
     `User` 에 세션·토큰이 없으므로 `LoginFailedException` 은 `application/exception` 이고,
     `RawPassword` 가 던지는 `InvalidPasswordException` 은 `domain/exception` 이다
   - 반면 에러 코드 enum 은 계층이 아니라 컨텍스트당 하나다. 쪼개면 예외를 한 계층 옮기는
     리팩터링이 API 변경이 된다. `common` 에는 기반 타입(`BusinessException`)과 `CommonErrorCode` 까지만
   - `ErrorCode` 는 `HttpStatus` 를 모른다. 도메인은 `ErrorKind` 까지만 알고 상태 매핑은 핸들러가 한다
   - 응답에는 `errorCode`, 로그에는 `logMessage`, 둘은 `traceId` 로 잇는다. `detail` 은 비어 있는 게 정상
   - 4xx 는 WARN 스택 없이, 5xx 는 ERROR 스택 포함
   - **시큐리티 필터 단계의 401·403 도 같은 계약을 따른다.** `@RestControllerAdvice` 를 안 거치므로
     `AuthenticationEntryPoint`/`AccessDeniedHandler` 를 `exceptionHandling` 과 `oauth2ResourceServer`
     **양쪽에** 등록해야 한다. 한쪽만 하면 토큰이 있고 검증에 실패한 경우가 빠진다 — 두 번 뚫린 자리다
   - 로그에 비밀번호를 남기지 않는다

## 표준 형태

- VO 는 `of()` 팩토리에서 정규화하고, 실패하면 에러 코드를 가진 도메인 예외를 던진다.
  `require` 를 쓰지 않는다 — `IllegalArgumentException` 하나로는 클라이언트가 이유를 구분할 수 없다.
  길이는 `codePointCount` 로 센다(`String.length` 는 이모지를 2자로 센다)
- 매퍼는 `reconstitute()`, 그 외 전부 `of()`. `reconstitute()` 가 검증하지 않는 것은 의도다 —
  `of()` 로 복원하면 규칙을 조이는 순간 기존 데이터를 읽을 수 없게 된다
- 애그리거트의 식별자는 nullable. `null` = 아직 저장되지 않음. 센티넬을 쓰지 않는다
- Command 는 원시 타입만 받는다. enum 도 예외가 아니다 — 도메인 타입 변환은 서비스가 한다
  (`UserRole.of(command.role)`). `application` 이 계약이라 여기서 도메인 enum 을 받으면 상수명이
  곧 API 계약이 된다. `@RequestParam` 의 도메인 enum 은 그대로 둔다(입력 경계는 안전, 출력은 이미 `String`)
- 유일성·존재는 DB 가 보장한다. 응용 계층의 `existsBy...` 는 친절한 오류용이고 동시 요청을 막지 못한다.
  이름 붙인 제약(`uk_users_username`, `fk_wallets_user`)이 실제 보장이고, 어댑터가 그 이름으로 번역한다
- **제약을 번역하는 어댑터는 `saveAndFlush` 를 쓴다.** `save()` 만 쓰면 UPDATE 가 커밋 시점에야
  flush 되어 CHECK·`@Version` 위반이 `catch` 를 지나친다. INSERT 는 IDENTITY 채번 때문에 우연히
  통과하므로 가입 테스트는 통과하고 잔액 음수 방지만 안 잡힌다. 회귀 테스트는 UPDATE 경로로 잡는다.
  낙관적 락 충돌은 새 코드를 만들지 말고 기존 상태 예외로 번역한다
- 잔액을 바꾸는 입구는 `Wallet.record()` 하나다. 반환값이 곧 저장할 원장 엔트리라, 원장을 빠뜨리는
  호출이 존재할 수 없다. 부호는 `Money` 가 아니라 `LedgerEntryType.direction` 이 나른다
  (`Money` 는 음수를 못 갖는다). 부호 있는 `Long` 이 도메인에 들어오는 자리는 `Adjustment` 하나뿐이다
- 환전은 요청 시점에 차감한다. 승인은 상태만 바꾼다 — 돈은 이미 나갔다.
  `ApproveWithdrawalRequestService` 에 `WalletRepository` 가 없는 것은 실수를 막기 위한 것이다
- 남의 리소스는 403 이 아니라 404 다. 서비스가 조회 단계에서 소유자까지 확인한다.
  도메인의 소유자 검사는 불변식으로 남긴다 — "안 쓰이는 코드" 로 보고 지우지 않는다
- 지갑은 명령 경로에서 lazy 로 만들어진다. 조회 경로에서는 만들지 않는다
  (`GET /api/wallet` 은 잔액 0). 그래서 "지갑 없음" 에러 코드가 없다
- 문자열 컬럼은 `text`. 길이 제약이 DB 에 없으므로 VO 가 유일한 방어선이다
- 외부 리소스는 키만 저장하고 URL 은 presentation 이 조립한다
- 페이지 응답은 `PagedModel`(`spring.data.web.pageable.serialization-mode: via_dto`)
- **KDoc 안에 `/api/admin` 와일드카드를 그대로 쓰지 않는다.** 별표-슬래시가 주석을 닫아
  `Unclosed comment` 가 엉뚱한 줄을 가리킨다
- 액세스 토큰은 JWT, 리프레시 토큰은 불투명하다. 통일하지 않는다. 리프레시 토큰에서 userId 를
  유도할 수 있게 만들지 않는다(`auth:refresh:{sha256}` 인덱스로만). 그 인덱스는 회전해도 지우지 않고
  TTL 로만 소멸시킨다 — 지우면 재사용 탐지가 무력화된다
- 역할 변경은 액세스 토큰 블랙리스트 + 갱신으로 반영한다. 리프레시 토큰은 살려둔다.
  최초 관리자 한 명만 DB 로 직접 만들고, 이후는 `POST /api/admin/users/{id}/role` 을 쓴다
- top-level `private` 함수는 이름이 패키지에서 충돌하지 않는 대신 **다른 파일에서 보이지 않는다.**
  파일 간에 헬퍼를 공유할 수 없어 `requireUserId()` 가 컨트롤러마다 복사되어 있다

## git flow

`master`(배포·태그) ← `release/*` ← `develop` ← `feature/*`. `hotfix/*` 는 `master` 에서 분기해 양쪽에 병합.

- `master` 에 직접 커밋하지 않는다
- 브랜치는 컨텍스트 접두(`feature/user-signup`), 커밋은 Conventional Commits + 컨텍스트 스코프

## 아직 하지 않은 것

- 스키마 관리 — `ddl-auto: update` 를 계속 쓴다. Flyway 로 갈 계획이 없다.
  추가만 하고 아무것도 지우지 않으므로 컬럼 삭제·이름/타입 변경은 손으로 SQL 을 친다.
  Hibernate 가 못 만드는 제약(FK)은 `src/main/resources/data.sql` 의 멱등 DDL 로 건다.
  **`data.sql` 의 문장 구분자는 `;` 가 아니라 `@@@`** — 기본 분할기가 `do $$ ... $$` 안의 `;` 에서 자른다
- ArchUnit 의존성 테스트 — 게임이 둘 이상 생기면 도입. 목표는 BuckPal 과 같은 3줄:
  `application.doesNotDependOn(adapters)` / `domainDoesNotDependOnAdapters()` / `adapters.dontDependOnEachOther()`
- 클라이언트 single-flight(토큰 갱신 경합), 프로필 이미지 업로드, 닉네임·비밀번호 변경, 회원 탈퇴,
  관리자 화면, Micrometer Tracing
- 로그인 응답 시간이 아이디 존재 여부를 노출한다 — 더미 해시 방어는 `3e4d64e` 에서 의도적으로
  제거했다. 되살리려면 위협 모델부터 정해라
