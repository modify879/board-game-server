# board-game

여러 종류의 게임을 올릴 실시간 멀티플레이 서버.

Kotlin 2.3 / Spring Boot 4.1 / Java 25 / PostgreSQL + Redis(세션·토큰 블랙리스트) / 단일 Gradle 모듈.

이 문서는 일반적인 DDD 설명이 아니라 **이 프로젝트에서 실제로 깨지기 쉬운 규칙**만 담는다.

---

## 명령어

```bash
./gradlew build          # 전체 빌드
./gradlew test           # 테스트
./gradlew bootRun        # 로컬 실행 (compose.yaml 의 PostgreSQL·Redis 자동 기동)
```

로컬 인프라(PostgreSQL 18, Redis 8)는 `compose.yaml` 로 정의되어 있고 `bootRun` 시 스프링이 띄운다.
Docker 가 실행 중이어야 한다.
접속 정보는 `application.yaml` 에 적지 않는다 — Docker Compose 지원이 자동으로 연결한다.

---

## 테스트

하나만 돌릴 때는 `./gradlew test --tests '*NicknameTest'`.

테스트는 Testcontainers 로 자기 컨테이너를 띄운다. `compose.yaml` 과 무관하며 역시 Docker 가 필요하다.
도메인 테스트는 스프링도 컨테이너도 없이 돈다 — 그게 도메인을 분리해서 얻는 것이다.

- **통합 테스트에는 `@Import(TestcontainersConfiguration::class)` 가 필요하다.**
  빠뜨리면 "Failed to determine a suitable driver class" 로 컨텍스트가 뜨지 않는다
- **목 라이브러리를 쓰지 않는다.** 인메모리 페이크를 테스트 파일 안에 직접 만든다
  (`SignUpServiceTest` 의 `FakeUserRepository` 참조)
  - **페이크 이름에는 테스트별 접두를 붙인다**(`RequestFakeWalletRepository`, `RejectFakeWalletRepository`).
    Kotlin 의 top-level `private` 클래스는 파일 스코프가 아니라 패키지 레벨 JVM 클래스라,
    같은 패키지의 두 테스트가 `FakeWalletRepository` 를 함께 쓰면 재선언 오류로 컴파일이 깨진다.
- **예외는 메시지가 아니라 `errorCode` 로 검증한다.** 메시지로 검증하면 규칙 8 이 무의미해진다
- 테스트 이름은 백틱을 쓴 한국어 문장으로 쓴다
- **컨테이너가 필요한 테스트는 클래스 이름에 `IntegrationTest` 접미사를 붙인다.**
  이름만 보고 Docker 가 필요한지 알 수 있어야 한다. 클래스 이름을 그대로 딴
  `XxxAdapterTest` 같은 이름은 가벼운 단위 테스트처럼 보여서 오해를 부른다
  (`XxxControllerTest` 는 스프링 관례상 `@WebMvcTest` 슬라이스를 뜻하기도 한다).
  나중에 빠른 테스트와 느린 테스트로 빌드를 쪼갤 때 이 접미사가 그대로 기준이 된다

**규칙을 테스트로 못 박을 때는 성격이 다른 경로를 여럿 잡아라.**
이 저장소에서 세 번 같은 방식으로 뚫렸다 — 중복 검사는 응용 계층 사전 체크 경로만 타서
DB 제약 위반 변환이 한 번도 실행되지 않았고, 오류 계약은 스프링이 body 를 미리 만들어 주는
경로만 타서 `body=null` 로 오는 405·404 가 통째로 빠져 있었다.
대표 케이스 하나를 통과했다고 그 규칙이 검증된 것이 아니다.

**wallet 통합 테스트는 실제 `users` 행을 먼저 만들어야 한다.** `fk_wallets_user` 때문에
합성 userId(`System.nanoTime()` 등)로 지갑을 열면 FK 위반으로 떨어진다. `UserRepository` 를
주입받아 사용자를 만들고 그 id 를 써라(`WalletRepositoryAdapterIntegrationTest` 참조).
충전·환전 **요청** 테이블에는 FK 가 없어 거기서는 합성 id 가 통한다 — 이 비대칭이 헷갈리는 자리다.

---

## 아키텍처

헥사고날로 경계를 잡고, 그 안쪽 도메인은 DDD 전술 패턴으로 설계한다.

### 4계층

```
com.jsm.boardgame
├── common/                       # 기술 설정·횡단 관심사만. 도메인 개념 금지
│   ├── config/                   # 스프링 설정 (Security, Clock, Kotlin JDSL)
│   ├── error/                    # 오류 계약. **domain 이 import 하는 유일한 common 패키지**
│   │                             #   (ErrorCode/ErrorKind/BusinessException)
│   ├── web/                      # HTTP 경계 구현. domain 은 절대 참조하지 않는다
│   │                             #   (전역 예외 핸들러, 시큐리티 필터 핸들러, traceId 필터)
│   └── persistence/              # 어댑터가 쓰는 영속 유틸 (제약명 파싱)
│
└── user/                         # ← 모든 바운디드 컨텍스트가 이 형태를 따른다
    ├── domain/
    │   ├── model/                # 애그리거트, 엔티티, VO — 순수 Kotlin
    │   ├── repository/           # 출력 포트 (애그리거트를 다룸)
    │   ├── service/              # 도메인 규칙이 필요로 하지만 스스로 구현 못 하는 출력 포트
    │   │                         #   (해싱, 셔플, 주사위)
    │   └── exception/            # 이 컨텍스트의 에러 코드와 도메인 예외
    ├── application/
    │   ├── command/
    │   │   ├── usecase/          # UseCase 인터페이스 + Command (입력 포트)
    │   │   └── service/          # UseCase 구현
    │   ├── port/                 # 규칙이 아니라 유스케이스가 필요로 하는 출력 포트
    │   │                         #   (세션 저장소, 토큰 발급기)
    │   ├── exception/            # 도메인 불변식이 아닌, 유스케이스의 실패 (로그인 실패, 토큰 무효)
    │   └── query/
    │       ├── service/          # 조회 서비스
    │       ├── port/             # 조회 출력 포트
    │       └── view/             # 응답 DTO
    ├── infrastructure/
    │   ├── persistence/
    │   │   ├── entity/           # JpaEntity, Spring Data, 매퍼
    │   │   └── adapter/          # 출력 포트 구현
    │   ├── security/
    │   │   ├── adapter/          # 인터페이스를 구현하는 것 — 우리 포트든 프레임워크 SPI든
    │   │   └── config/           # 빈 조립과 설정값 (@ConfigurationProperties)
    │   └── acl/                  # 다른 바운디드 컨텍스트를 부르는 어댑터. DB 를 건드리지 않으므로
    │                             #   persistence 에 두지 않는다 — 여기 모아야 교차 참조가 눈에 띈다
    └── presentation/
        ├── config/               # 이 계층의 @ConfigurationProperties 와 그걸 읽는 조립기
        ├── exception/            # presentation 이 소유하는 예외 (비밀번호 확인 불일치)
        ├── rest/                 # Controller
        │   ├── request/          # 요청 DTO + toCommand()
        │   └── response/         # 응답 DTO + from()
        └── ws/                   # WebSocket 핸들러
```

**한 패키지에 역할이 섞여 있으면 가른다. 크기는 기준이 아니다.**
명령은 `usecase`(입력 포트) / `service`(구현), 조회는 `service`/`port`/`view`,
영속은 `entity`/`adapter`.
파일이 하나뿐인 하위 패키지가 생겨도 그대로 둔다 — **모양의 일관성이 탐색 비용보다 우선한다.**
컨텍스트가 달라도 같은 자리에 같은 것이 있어야, 새 컨텍스트를 만들 때 판단할 것이 없다.
`domain/model`·`domain/repository`·`domain/exception` 처럼 패키지명 자체가 이미 역할인 곳은
더 가르지 않는다.

명령/조회 절단면(규칙 3)이 역할 분리보다 **위**에 온다. 헥사고날 참조 구현(BuckPal)은
`port/in`+`service` 를 최상위에 두지만 거기엔 명령/조회 분리가 없다. 이 프로젝트는 규칙 3이
먼저이므로 `command/{usecase,service}` 가 맞다.

계층 이름은 `domain` / `application` / `infrastructure` / `presentation` 으로 통일한다.
`interfaces` 는 쓰지 않는다 — Kotlin 의 `interface` 키워드와 시각적으로 충돌하는데,
이 프로젝트는 포트 인터페이스를 `domain` 과 `application` 에 두므로 혼동이 크다.

헥사고날 대응: `presentation` = driving adapter, `infrastructure` = driven adapter,
`domain` 과 `application` 의 포트 = output port, `application/command/usecase` 의 UseCase = input port.

### 의존성 방향

```
presentation → application → domain ← infrastructure
```

- `domain` 은 아무것도 의존하지 않는다.
- `application` 과 `domain` 은 `infrastructure` 를 참조하지 않는다. 예외 없다.
- 바운디드 컨텍스트끼리 서로의 `domain` 을 참조하지 않는다.

**컨텍스트를 넘어야 할 때**: 다른 컨텍스트가 필요하면 **부르는 쪽이 포트를 소유하고**,
어댑터가 상대의 **공개된 `application`** 만 호출한다. 상대의 `domain`·`infrastructure` 는
어느 계층에서도 참조하지 않는다. `wallet/application/port/UserExistence`(wallet 이 소유,
아는 것은 Boolean 하나) ← `wallet/infrastructure/acl/UserExistenceAdapter`
(`user.application.query` 만 import). 포트를 거르고 `UserQueryService` 를 직접 부르면
안 되는 이유는 규칙 8 이다 — 실패가 `user` 의 errorCode 로 나가는데 깨진 규칙은 부르는 쪽의
규칙이다. 지금 교차 import 는 저장소 전체에 이 한 곳뿐이고, 늘어나면 경계를 다시 봐야 한다는
신호다.

---

## 바운디드 컨텍스트

| 컨텍스트 | 상태 | 책임 |
|---|---|---|
| `user` | 구현됨 | 사용자, 아이디, 비밀번호, 닉네임, 프로필 이미지, 로그인 세션, 역할 |
| `wallet` | 구현됨 | 잔액·원장, 충전/환전 요청과 관리자 승인·반려·직접 조정 |
| `holdem` | 미정 | 텍사스 홀덤 규칙 전부. **방/좌석을 자기 안에 둔다** |
| 각 게임 | 미정 | 그 게임의 규칙 전부 |

방/좌석을 별도 컨텍스트로 두지 않는 이유: 좌석 모델이 게임 규칙에 좌우된다.
홀덤의 좌석은 버튼 이동·블라인드 포스팅·사이드팟 자격까지 얽혀 있어, 게임 밖으로 빼면
그 게임에만 있는 개념이 공용 좌석 모델로 새어 나간다(규칙 1이 막으려는 것과 같은 힘이다).

### 게임을 추가할 때

- 새 게임은 **최상위에 자기 바운디드 컨텍스트**를 갖는다 (`com.jsm.boardgame.<game>`).
- `game/` 같은 상위 묶음을 두지 않는다 — 묶음이 있으면 그 아래 공유 패키지가 생긴다.
- 위 4계층 구조를 그대로 따른다.

---

## 규칙

### 1. 게임 간 공유는 없다

멀티 게임 플랫폼이 망가지는 가장 흔한 경로다.
`GameRule`, `Move`, `GameState` 같은 게임 공통 인터페이스를 만들면 모든 게임이 그 추상화에 맞춰 왜곡된다.

- 게임 컨텍스트끼리 서로를 import 하지 않는다. **공유 패키지를 만들지 않는다.**
- 게임 공통 상위 타입을 만들지 않는다. 게임을 갈아끼우는 다형성이 필요하면
  **애플리케이션 계층**에 둔다 (`GameLauncher.supports(gameType)` 같은 전략 패턴).
- `common` 에 도메인 개념을 넣지 않는다. `common` 에 `Player` 나 `Money` 가 들어가는 순간
  공유 패키지가 이름만 바꿔 부활한다. `common` 은 스프링 설정과 예외 처리까지다.
- 코드가 겹쳐 보여도 각 게임이 자기 카드·자기 재화·자기 셔플러를 갖는다. 다시 구현하는 편이 낫다.

**`wallet` 은 이 규칙의 예외가 아니라 애초에 대상이 아니다.** 규칙 1이 금지하는 것은
`GameRule`/`Move` 같은 **게임 규칙 상위 타입**이지 `user` 같은 비게임 컨텍스트가 아니다.
지갑 잔액은 게임이 끝나도 남아 나중에 환전되는 **정산 워크플로**이고, 게임 안의 재화
(홀덤의 `Chips`, 부루마블의 마블)는 그 게임 컨텍스트가 자기 VO 로 갖는다(규칙 7).
게임 컨텍스트는 `wallet` 을 **응용 계층에서만** 바이인/캐시아웃으로 부른다 —
게임의 `domain` 이 `wallet` 의 `domain` 을 참조하면 그때는 규칙 위반이다.

두 게임에 같은 이름의 클래스가 있는 것은 중복이 아니다.
같은 단어가 컨텍스트마다 다른 뜻을 갖는 것이 바운디드 컨텍스트의 정의이며,
유비쿼터스 언어는 컨텍스트 안에서만 통한다.

### 2. 도메인 모델과 JPA 엔티티는 항상 분리한다

예외 없다.

- `domain/model/` 은 순수 Kotlin.
- `infrastructure/persistence/` 에 `XxxJpaEntity` 와 확장 함수 매퍼를 둔다.
- `domain/` 아래에서 `jakarta.persistence.*`, `org.springframework.*` import 금지.

### 3. 명령과 조회의 경로가 다르다

판단 기준은 **"이게 상태를 바꾸는가?"** 하나뿐이다.

| | 명령 | 조회 |
|---|---|---|
| 도메인 | 반드시 애그리거트를 거친다 | 거치지 않는다 |
| 불변식 | 도메인에서만 지킨다 | 지킬 것이 없다 |
| 결과 | 없음 또는 식별자 | 응답 DTO 직접 프로젝션 |
| 입력 포트 | UseCase 인터페이스를 **둔다** | **두지 않는다.** 서비스 클래스 하나 |
| 출력 포트 | `domain/repository` | `application/query` |
| 쿼리 작성 | Spring Data 파생 쿼리로 충분 | **Kotlin JDSL** |

명령의 결과가 식별자로 끝나지 않는 경우도 있다 — 로그인·갱신은 토큰을 돌려줘야 한다.
그럴 때도 **출력 포트의 타입을 그대로 돌려주지 않는다.** 입력 포트 전용 타입을 따로 둔다
(`AuthTokens` vs 출력 포트의 `IssuedTokens`) — 안 그러면 어댑터 사정으로 늘어난 필드가
곧바로 presentation 의 계약이 되고, jti 처럼 내보내면 안 되는 값이 주석으로만 막힌다.

조회가 `JpaEntity → 도메인 → 응답 DTO` 로 두 번 매핑되면 안 된다.
생성자 프로젝션으로 응답 DTO 를 바로 만든다.

JDSL 은 "모든 DB 접근"이 아니라 조회 경로에만 쓴다.
경계는 이미 있는 명령/조회 분리선과 같으므로 새로 판단할 것이 없다.

JPQL 문자열을 쓰지 않는 이유: 조건이 선택적인 쿼리(검색·필터·랭킹)가 생기면
문자열을 이어붙이거나 쿼리를 여러 벌 두게 되고, 필드 이름이 바뀌어도 컴파일이 통과한다.

단건 조회에서는 JDSL 이 JPQL 문자열보다 장황하다. 그건 규칙을 하나로 유지하는 값이다.

입력 포트를 명령에만 두는 이유: 의존성 역전이 필요한 건 **나가는** 방향뿐이다.
들어오는 방향은 `presentation → application` 이 이미 올바른 방향이라 뒤집을 게 없다.
조회는 계약을 역전할 이유가 없으므로 클래스 하나로 끝낸다.

출력 포트는 명령·조회 모두 둔다. 조회라고 해서 `application` 이 `infrastructure` 를 직접 참조하지 않는다.

### 4. UseCase 이름은 의도를 드러낸다

- `SignUpUseCase`, `ChangeNicknameUseCase` — 유스케이스당 하나
- `UserService` / `UserServiceImpl` 처럼 엔티티당 1:1 로 대응하는 별칭 인터페이스 금지

### 5. 무작위성은 도메인이 소유하지 않고 주입받는다

셔플·주사위를 도메인 안에서 직접 호출하면 규칙을 테스트할 수 없다.
각 게임 컨텍스트가 자기 포트를 정의하고 주입받는다.

```kotlin
fun interface Shuffler { fun shuffle(deck: List<Card>): List<Card> }
fun interface DiceRoller { fun roll(count: Int): List<Int> }
```

테스트에서 고정된 패·눈을 주입해 규칙을 스프링 없이 검증한다.
이것이 도메인을 분리해서 얻는 실질적 이득이다.

시계도 같은 이유로 주입받지만 게임마다 뜻이 달라지지 않아 컨텍스트별 포트를 만들지 않는다 —
`common/config` 의 `Clock` 빈 하나를 공유한다. `Instant.now()` 가 한 곳만 남아도 TTL·유예 창 테스트가 흔들린다.

### 6. 히든 정보는 뷰어별로 마스킹한다

상대 패가 보이면 안 되는 게임에서 이게 무너지면 게임이 성립하지 않는다. 보안 규칙으로 다룬다.

- **WebSocket 으로 도메인 객체나 전체 게임 상태를 그대로 내보내지 않는다.**
- 반드시 `game.viewFor(viewer: PlayerId): XxxView` 를 거쳐 관찰자 시점으로 변환한 뒤 전송한다.
- 브로드캐스트의 기본값은 "전원에게 같은 메시지"가 아니다. **좌석별로 다른 페이로드**가 기본이다.

### 7. 재화는 원시 타입으로 다루지 않는다

베팅액·자산은 각 게임 컨텍스트가 자기 VO 로 갖는다. `Int`/`Long` 으로 다루지 않는다.
음수 방지와 연산 캡슐화가 목적이다. 게임 간에 이 타입을 공유하지 않는다.

### 8. 오류는 코드로 계약하고, 문구는 클라이언트가 만든다

구현은 `common/error/`·`common/web/` 과 각 컨텍스트의 `domain/exception/` 을 참조한다.
여기에는 코드만 봐서는 되돌리기 쉬운 결정의 이유만 적는다.

- 예외는 규칙을 소유한 컨텍스트가 소유한다. `common` 에 범용 예외를 두지 않는다
  — 타입이 아니라 메시지 문자열이 의미를 나르게 되어 아이디 중복인지 닉네임 중복인지 구분할 수 없다.
  `common` 에 두는 것은 기반 타입(`BusinessException`)과 필터 단계의 기술 분류(`CommonErrorCode`)까지다
- `ErrorCode` 는 `HttpStatus` 를 모른다. 도메인이 참조하는 타입이라 스프링이 들어오면 규칙 2가 깨진다.
  도메인은 `ErrorKind`(INVALID/UNAUTHORIZED/FORBIDDEN/NOT_FOUND/CONFLICT)까지만 알고, 상태 매핑은 핸들러가 한다
- 응답에는 `errorCode`, 로그에는 `logMessage`. 둘은 `traceId` 로 잇는다
  — 분리만 하고 잇지 않으면 사용자 신고를 받아도 어느 로그인지 찾을 수 없다
- 서버는 사용자 문구를 내려보내지 않는다. `detail` 은 **비어 있는 게 정상**이다.
  `type`/`title` 도 스프링 기본값 그대로 둔다
- 4xx 는 WARN 에 스택 없이, 5xx 는 ERROR 에 스택 포함
  — 중복 가입 시도마다 스택이 찍히면 로그가 쓸모없어진다
- **스프링 시큐리티 필터 단계의 401·403 도 같은 계약을 따라야 한다.**
  필터는 `@RestControllerAdvice` 를 거치지 않으므로 `AuthenticationEntryPoint`/`AccessDeniedHandler` 를
  따로 물려야 한다. `oauth2ResourceServer` DSL 은 자체 엔트리포인트를 등록하므로
  `exceptionHandling` 에만 등록하면 **토큰이 있지만 검증에 실패한 경우**가 빠진다 — 둘 다 등록해야 한다.
  이 저장소에서 실제로 두 번 뚫린 자리다
- 로그 메시지에 비밀번호를 남기지 않는다. `RawPassword`·`PasswordHash` 는 `toString()` 이 마스킹되어 있다

**예외는 그 실패를 소유한 계층에 둔다. `domain/exception` 은 "이 컨텍스트의 예외 전부"가 아니다.**
판단 기준은 포트를 `domain/service` 와 `application/port` 로 가를 때 쓴 것과 같다 —
**애그리거트가 그 단어를 아는가.** `User` 에는 세션도 토큰도 로그인도 없으므로
`LoginFailedException`·`InvalidRefreshTokenException` 은 `application/exception` 이고,
`RawPassword` 가 직접 던지는 `InvalidPasswordException` 은 `domain/exception` 이다.
`presentation` 도 자기 예외를 갖는다(`PasswordConfirmMismatchException` — 애그리거트에 대응
필드가 없는 입력 폼의 관심사).

**반면 에러 코드 enum 은 계층이 아니라 컨텍스트당 하나다.** `UserErrorCode` 에는 세 계층의
코드가 모두 들어 있다. 계층별로 쪼개면 **클라이언트가 보는 계약이 우리 패키지 구조를 따라
흔들린다** — 예외를 한 계층 옮기는 리팩터링이 API 변경이 되어선 안 된다.
`XxxNotFoundException` 을 domain 에 두는 것도 같은 이유로 그대로 둔다(문헌도 갈린다).

---

## 표준 형태

`user` 컨텍스트가 참조 구현이다. 새 컨텍스트는 그 파일 배치를 그대로 따른다.
여기에는 **코드를 봐도 의도가 드러나지 않는 것**만 적는다.

### Command 는 원시 타입만 받는다. enum 도 예외가 아니다

`Command` 필드는 `Long`/`String` 이고, 도메인 타입 변환은 서비스가 한다
(`UserRole.of(command.role)`). `ChangeUserRoleCommand` 가 `UserRole` 을 직접 받던 것이
13개 중 유일한 이탈이었다. `application` 이 계약이므로 여기서 도메인 타입을 받으면
**도메인 enum 상수명이 곧 API 계약**이 되고, 이름을 다듬는 리팩터링이 클라이언트를 깨뜨린다.
변환이 서비스로 오면 알 수 없는 값도 `USER_ROLE_INVALID` 로 규칙 8 을 따라 나간다 —
잭슨 역직렬화에서 걸리면 `errorCode` 없는 응답이 된다.

**`@RequestParam` 의 도메인 enum 은 그대로 둔다**(`AdminWalletController` 의 `status`).
업계 통설은 enum 이 **출력**에서 위험하고 입력에서는 상대적으로 안전하다는 것이고
(응답에 값을 추가하면 클라이언트가 깨진다), 이 저장소는 이미 위험한 쪽만 막아 뒀다 —
`DepositRequestView.status` 는 `String` 이다. Hombergs 도 매핑 전략을 코드베이스 전체에
하나로 강제하지 말라고 못 박는다. 입력 경계에서 도메인 enum 이 주는 컴파일 검사를 포기할
이유가 없다. **`presentation` 은 지점별 판단이 허용되지만 `application` 계약은 아니다** —
이 비대칭이 의도다.

### VO 는 팩토리에서 정규화하고, 실패하면 에러 코드를 가진 도메인 예외를 던진다

```kotlin
@JvmInline
value class Nickname private constructor(val value: String) {
    companion object {
        fun of(raw: String): Nickname {
            val normalized = ... // NFC 정규화 → trim → 연속 공백 축약
            if (...) throw InvalidNicknameException(UserErrorCode.NICKNAME_LENGTH, "...")
            return Nickname(normalized)
        }
    }
}
```

- **`private` 생성자 + `of()` 팩토리인 이유**: value class 는 `init` 에서 값을 바꿀 수 없어 정규화를 할 수 없다.
  정규화가 필요 없는 VO(`UserId`, `PasswordHash`)는 일반 생성자 + `init` 검증을 쓴다. 이 비대칭은 의도된 것이다.
- **`require` 를 쓰지 않는 이유**: `IllegalArgumentException` 하나로는 클라이언트가 길이 문제인지
  금지 문자 문제인지 구분할 수 없고, 도메인이 UX 문구를 소유하게 된다 (규칙 8).
- 길이는 `codePointCount` 로 센다. `String.length` 는 UTF-16 코드 단위라 이모지가 2자로 세어진다.

### 저장된 값을 되돌릴 때는 검증하지 않는다

`of()` 는 **사용자 입력용**이고, 영속 계층에서 복원할 때는 `reconstitute()` 를 쓴다.
`reconstitute()` 는 검증도 정규화도 하지 않는다 — **빠뜨린 게 아니라 의도한 것이다.**

복원할 때 `of()` 를 쓰면 **입력 검증 규칙을 이미 저장된 데이터에 다시 적용**하게 되고,
규칙을 조이는 순간 기존 데이터를 읽을 수 없게 된다. 닉네임 상한을 20자에서 12자로 줄이면
15자 닉네임을 가진 기존 사용자가 로그인조차 못 하고, 원인은 인증 코드가 아니라 매퍼에 있어 찾기도 어렵다.
도메인은 자기 저장소에서 나온 값을 신뢰한다.

판단할 것이 없는 기계적인 규칙이다 — **매퍼는 `reconstitute()`, 그 외 전부 `of()`.**
애그리거트도 같은 이름을 쓴다(`User.reconstitute()`).
규칙을 조인 뒤 기존 데이터를 정리해야 한다면 그건 별도의 마이그레이션 작업이지 읽기 경로가 할 일이 아니다.

### 애그리거트의 식별자는 nullable 이다

`val id: UserId?` 에서 `null` 은 아직 저장되지 않았다는 뜻이다.
`UserId(0)` 같은 센티넬을 쓰면 JPA 의 관례를 도메인이 물려받는 것이다.
신규 생성은 `register()`, 영속 계층에서의 복원은 `reconstitute()` 로 의도를 갈라 놓는다.

### 유일성은 DB 가 보장한다

응용 계층의 `existsBy...` 사전 체크는 친절한 오류 응답을 위한 것이고 **동시 요청을 막지 못한다.**
**이름을 붙인** unique 제약(`uk_users_username`)이 실제 보장이다 — 이름이 있어야 어댑터가 어느 제약이
깨졌는지 구분해 도메인 예외로 변환할 수 있다. 스프링 예외가 `application` 까지 올라가면 의존성 방향이 깨진다.

외래키도 같은 구조다. `AdjustWalletBalanceService` 의 `UserExistence` 사전 체크는 친절한 오류용이고
`fk_wallets_user` 가 실제 보장이며, 둘 다 `WALLET_OWNER_NOT_FOUND` 로 떨어진다.

### 제약 위반을 도메인 예외로 번역하려면 `saveAndFlush` 여야 한다

`jpa.save()` 만 쓰면 **UPDATE 는 트랜잭션 커밋 시점에야 flush 되므로** CHECK 제약 위반과
`@Version` 충돌이 어댑터의 `catch` 를 **지나쳐** 버린다. 번역이 통째로 죽는데,
신규 INSERT 는 IDENTITY 채번 때문에 우연히 즉시 나가므로 **가입·최초 생성 테스트는 통과한다.**
"충전은 되는데 잔액 음수 방지만 안 잡히는" 형태로만 드러나는 함정이다.

그래서 제약을 번역하는 어댑터는 `saveAndFlush` 를 쓰고, 회귀 테스트는 **INSERT 경로가 아니라
UPDATE 경로**로 잡는다 (`WalletRepositoryAdapterIntegrationTest`).

낙관적 락 충돌은 새 에러 코드를 만들지 말고 **이미 있는 상태 예외로 번역한다** —
동시 승인 경합의 실제 의미가 `DEPOSIT_REQUEST_ALREADY_PROCESSED` 이고,
클라이언트는 도메인 상태 검사로 떨어졌는지 DB 락으로 떨어졌는지 구분할 필요가 없다.

### 잔액의 진실은 원장이고 `balance` 는 스냅샷이다

`Wallet.balance` 는 빠른 조회를 위한 캐시이고 append-only 인 `LedgerEntry` 가 진실이다.
둘은 같은 트랜잭션에서 갱신한다. 엔트리에 `balanceAfter` 를 남겨 원장만 훑어도 잔액을 검증할 수 있다.

**잔액을 바꾸는 입구는 `Wallet.record()` 하나다.** `credit`/`debit` 로 가르지 않은 이유는
잔액만 바꾸고 원장을 빠뜨리는 호출이 존재할 수 없게 하기 위해서다 — 반환값이 곧 저장해야 할 엔트리다.

부호는 `Money` 가 아니라 `LedgerEntryType.direction` 이 나른다. `Money` 가 음수를 못 갖기 때문에
관리자 조정도 `ADMIN_ADJUSTMENT_CREDIT`/`_DEBIT` 두 상수로 갈라져 있다 —
하나로 합치면 부호를 어딘가에 따로 실어야 하고, 그 순간 원장 합산으로 잔액을 검증할 수 없게 된다.

**이중기입은 쓰지 않는다.** 대신 `referenceType`/`referenceId` 로 모든 엔트리가 출처를 가리킨다.

### 한 트랜잭션이 애그리거트 여럿을 고치는 것은 의도된 이탈이다

Vernon 의 "한 트랜잭션에 애그리거트 하나" 원칙을 지키지 않는다. `ApproveDepositRequestService` 는
`DepositRequest`·`Wallet`·`LedgerEntry` 셋을 한 트랜잭션에서 고친다.
`Wallet`+`LedgerEntry` 2개는 **구조적으로 피할 수 없다** — 원장은 무한히 늘어나는 컬렉션이라
애그리거트 안에 넣을 수 없고, `balance` 를 지우고 매번 SUM 하면 조회 비용이 폭증한다.
스냅샷+원장을 택한 대가다. 세 번째를 떼어내려면 아웃박스+이벤트+정산 대조가 필요하고
"승인됐는데 잔액은 그대로"인 창이 생긴다. 이 규모에서는 순손해다.
Vernon 의 원칙은 확장성과 경합을 위한 것이지 정확성을 위한 것이 아니다.

### 환전은 요청 시점에 차감한다

요청만 걸어두고 차감을 승인 시점으로 미루면, 요청 후 게임에서 다 잃은 뒤 승인되어 잔액이 음수가 된다.
요청 시 즉시 차감하고 `WITHDRAWAL_HOLD` 를 남긴다. 반려·취소는 `WITHDRAWAL_REFUND` 로 환급하고,
**승인은 상태만 바꾼다** — 돈은 이미 나갔다. `ApproveWithdrawalRequestService` 가 `WalletRepository` 를
주입받지 않는 것은 실수를 막기 위한 것이다. 생성자에 없으면 건드릴 수 없다.

이중 환급은 도메인 상태 전이(`PENDING` 아니면 예외)와 `@Version` 두 겹으로 막는다. **둘 다 테스트한다.**

### 남의 리소스는 403 이 아니라 404 다

충전·환전 요청 취소에서 **"소유자가 아님" 과 "존재하지 않음" 을 가르지 않는다.** 둘이 갈리면
인증된 사용자가 아무 id 나 넣어보는 것만으로 남의 요청이 존재하는지 열거할 수 있다.
서비스가 조회 단계에서 소유자까지 확인하고 `...NotFoundException` 을 던진다.

도메인의 소유자 검사(`DepositRequest.cancel` 의 `NOT_REQUEST_OWNER`)는 불변식으로 **남긴다** —
서비스가 먼저 걸러서 HTTP 로는 나오지 않을 뿐이다. 이걸 "쓰이지 않는 코드" 로 보고 지우면
애그리거트가 자기 소유권을 안 지키게 된다.

`WalletApiIntegrationTest` 가 **남의 요청과 없는 요청이 같은 응답인지**를 검증한다.
한쪽만 검증하면 이 규칙은 다시 뚫린다.

### 지갑은 돈이 움직일 때 lazy 로 만들어진다

회원가입이 `wallet` 을 부르면 컨텍스트가 결합된다. `user` 는 `wallet` 을 전혀 모른다.
지갑은 충전 승인·환전 요청·관리자 조정처럼 **명령 경로**에서 없으면 만들어진다.

**조회 경로에서는 만들지 않는다.** `GET /api/wallet` 은 지갑이 없으면 잔액 0 을 돌려준다 —
조회가 상태를 바꾸면 규칙 3이 깨진다. 그래서 "지갑 없음" 에러 코드는 존재하지 않는다.

### 문자열 컬럼은 `text` 다

PostgreSQL 에서 `text` 와 `varchar(n)` 은 성능이 같다.
대신 길이 제약이 DB 에서 사라지므로 **VO 가 유일한 방어선**이 된다.

### 외부 리소스는 키만 저장하고 URL 은 presentation 이 조립한다

프로필 이미지는 `profile_image_key` 만 저장한다(`null` = 기본 이미지).
CDN 도메인은 인프라 설정이라 도메인 모델이 알면 안 되고, 버킷이나 환경이 바뀌어도 DB 를 건드리지 않는다.

### 역할 변경은 재로그인이 아니라 액세스 토큰 블랙리스트 + 갱신으로 반영한다

`ChangeUserRoleService` 는 역할을 저장한 뒤 **현재 액세스 토큰만** 블랙리스트에 넣는다.
리프레시 토큰은 살려둔다 — 클라이언트가 이미 타는 401 → `POST /api/auth/refresh` 경로가
새 역할이 박힌 토큰을 넘겨준다. 강등 반영은 똑같이 즉시인데 사용자는 로그아웃되지 않고,
클라이언트 코드는 한 줄도 바뀌지 않는다. 승격도 같은 흐름으로 대칭이다.

대가로 `RefreshTokenService` 가 갱신 때마다 `UserRepository.findById` 로 역할을 읽는다.
역할을 Redis 세션 문자열에 끼워 넣으면 DB 를 안 타지만 `RedisAuthSessionStore` 의 직렬화 포맷과
Lua 스크립트를 건드려야 한다 — 이미 두 번 버그가 난 자리다. DB 가 권한의 진실이라는 점에서도 맞다.

**닫히지 않은 경합이 하나 있다**(코드 주석에도 적혀 있다): 역할 변경 트랜잭션이 커밋되기 **전에**
들어온 갱신 요청은 옛 역할이 박힌 토큰을 받고, 그 jti 는 블랙리스트 대상에 잡히지 않는다.
강등이 최대 `access-token-ttl` 만큼 늦어진다. 두 문장의 순서를 어떻게 바꿔도 닫히지 않는다 —
"이 시각 이전에 발급된 토큰 전부 무효" 라는 기준이 있어야 닫힌다. 지금 규모에서 그 비용을 지불하지 않는다.

**최초 관리자 한 명만 DB 로 직접 만든다.** 이후는 `POST /api/admin/users/{id}/role` 을 쓴다 —
DB 직접 `UPDATE` 로는 토큰을 죽일 수 없어 즉시 강등이 불가능하다.
최초 관리자는 아직 세션이 없어 죽일 토큰도 없으므로 예외가 된다.

### 페이지 응답은 `PagedModel` 이다

`spring.data.web.pageable.serialization-mode: via_dto`. `Page` 를 그대로 직렬화하면 응답 JSON 이
`PageImpl` 의 내부 구조에 묶여 계약이 불안정해진다. 클라이언트는 `content` 와 `page` 를 보면 된다.

### 주석 안에 관리자 경로 와일드카드를 그대로 쓰지 않는다

Kotlin 은 블록 주석 중첩을 지원해서 KDoc 안의 별표-슬래시가 주석을 닫아버린다.
`Unclosed comment` 는 엉뚱한 줄을 가리켜 원인을 찾기 어렵다. `/api/admin` 이하 처럼 풀어 쓴다.

### 액세스 토큰은 JWT, 리프레시 토큰은 불투명하다

**형식이 다르다는 것 자체가 방어선이다.** 둘을 같은 형식으로 통일하지 않는다 — 같은 키로 서명된
같은 구조가 되는 순간 둘을 가르는 것은 클레임 한 칸뿐이고, 그걸 확인하는 자리를 한 군데만
빠뜨려도 리프레시 토큰이 액세스 토큰으로 통한다. "왜 둘이 다르냐, 통일하자" 가 개선처럼
보이는 것이 이 결정의 위험한 점이다.

**리프레시 토큰에서 userId 를 유도할 수 있게 만들지 않는다.** Redis 조회를 한 번 아끼려고
`{userId}.{랜덤}` 접두사를 붙이고 싶어지는데, `/api/auth/refresh` 는 `permitAll` 이고 회전
불일치의 반응이 "세션 전체 폐기" 라서, **숫자만 아는 사람이 아무 문자열이나 보내 남을
로그아웃시킬 수 있다.** 토큰 → 사용자 매핑은 `auth:refresh:{sha256(토큰)}` 인덱스로만 한다.

그 인덱스는 **회전해도 지우지 않는다. TTL 로만 소멸시킨다.** 청소 누락처럼 보이지만, 지우면
탈취된 옛 토큰이 "모르는 토큰" 으로 조용히 떨어져 재사용 탐지가 무력화된다.

---

## git flow

| 브랜치 | 분기 | 병합 대상 |
|---|---|---|
| `master` | — | 배포 가능 상태만. 태그를 단다 |
| `develop` | `master` | 통합 기준선 |
| `feature/*` | `develop` | `develop` |
| `release/*` | `develop` | `master` + `develop` |
| `hotfix/*` | `master` | `master` + `develop` |

- `master` 에 직접 커밋하지 않는다.
- 브랜치 이름은 컨텍스트를 접두로: `feature/user-signup`
- 커밋은 Conventional Commits, 스코프는 바운디드 컨텍스트: `feat(user): 닉네임 변경 유스케이스 추가`

---

## 아직 하지 않은 것

- **JWT 서명 키 교체** — `application.yaml` 에 개발용 키가 커밋되어 있다.
  저장소를 읽을 수 있는 사람은 누구나 임의 사용자로 토큰을 위조할 수 있다.
  운영에서는 `APP_JWT_SECRET` 환경변수로 반드시 덮어쓴다. 배포 전 필수 항목이다
- **클라이언트 single-flight** — 토큰 갱신 경합은 서버만으로 완전히 못 막는다.
  화면 하나에서 API 요청 여러 개가 동시에 401 을 받으면 각자 갱신을 시도하는데,
  서버는 그게 "같은 의도의 중복"인지 "진짜 여러 번의 시도"인지 알 방법이 없다 — 클라이언트만 안다.
  클라이언트에서 진행 중인 갱신이 있으면 새로 시작하지 말고 그 결과를 기다리게 하고,
  브라우저라면 탭 사이도 Web Locks 나 BroadcastChannel 로 묶어야 한다.
  서버 쪽은 이미 두 겹을 갖췄다 — 유예 창(응답 유실 재시도 허용)과 즉시 경합 가드(동시 중복은 거절).
  업계 권고가 이 둘을 함께 쓰는 것이다: 클라이언트 쪽은 정직한 중복을 막고, 서버 쪽은 탈취를 막는다
- **로그인 응답 시간이 아이디 존재 여부를 노출한다** — 계정 열거 방지는 `errorCode` 로만 한다.
  없는 아이디는 BCrypt 를 타지 않아 응답이 짧다(평균 77ms vs 3.6ms). 시간을 맞추던 더미 해시 방어는
  `3e4d64e` 에서 **의도적으로** 제거했다 — 모르고 빠진 게 아니다. 되살리려면 위협 모델부터 정해라
- **홀덤 2~5단계** — 이 저장소의 첫 게임은 텍사스 홀덤 캐시게임이고, 1단계(`wallet` + `ROLE_ADMIN`)까지 끝났다
  | 단계 | 브랜치 | 내용 |
  |---|---|---|
  | 2 | `feature/holdem-domain` | Card/Deck/Shuffler, 핸드 평가기, 베팅 라운드, 사이드팟, Hand 애그리거트. 스프링·컨테이너 없이 테스트 |
  | 3 | `feature/holdem-table` | Table/Seat, 방 생성·착석·바이인(wallet 호출)·기립, 영속, 재접속 복귀 조회 |
  | 4 | `feature/holdem-ws` | STOMP. CONNECT 인증·SUBSCRIBE 인가 인터셉터, 공개/개인 채널 분리, 타이머 |
  | 5 | `feature/holdem-recovery` | 진행 중 핸드 상태 스냅샷 영속, 재시작 복구 |

  4단계에서 지킬 것: **브로드캐스트 페이로드 타입에 홀카드를 담을 필드를 두지 않는다.**
  `/topic/tables/{id}` 에는 `TablePublicView` 만, `/user/queue/...` 에는 `SeatPrivateView` 만 흐르게 해
  규칙 6을 "좌석마다 루프 돌 것을 기억하라" 가 아니라 **컴파일러**로 보장한다.
- **핸드 히스토리를 남기지 않는다** — 감사·분쟁·통계 요구가 없다. 필요한 건 재시작 복구뿐이라
  이벤트 로그가 아니라 **진행 중 핸드의 상태 스냅샷 1행**을 PostgreSQL 에 둔다.
  이벤트 소싱은 재생 엔진이라는 **정상 경로와 다른 코드**를 하나 더 만드는데, 거기서 틀리면
  복구 시 돈이 틀어지고 그 오류는 서버가 죽었을 때만 드러난다
- 프로필 이미지 업로드 (스토리지 연동, presigned URL). 지금은 키를 저장할 자리만 있다
- 닉네임·비밀번호 변경, 회원 탈퇴
- **스키마 관리 방침** — `ddl-auto: update` 를 계속 쓴다. 운영 전 Flyway 로 전환할 계획이 **없다.**
  `ddl-auto: update` 는 **추가만 하고 아무것도 지우지 않는다** — 컬럼 삭제·이름 변경·타입 변경·
  데이터 이관을 하지 않는다. 그런 변경이 필요해지면 그때는 손으로 SQL 을 쳐야 하고, 이 선택의
  대가가 그것이다. **Hibernate 가 만들지 못하는 제약(FK 등)은 `src/main/resources/data.sql` 의
  멱등 DDL 로 건다** (`fk_wallets_user` 가 그 예다). 손으로 한 번 실행하는 방식을 쓰지 않는 이유는
  Testcontainers 의 빈 DB 에 제약이 없어 번역 경로가 검증되지 않기 때문이다.
  **`data.sql` 의 문장 구분자는 `;` 가 아니라 `@@@` 다**(`spring.sql.init.separator`) —
  스프링의 기본 분할기가 `do $$ ... $$` 블록 안의 `;` 에서 잘라 버리기 때문이다.
  여기에 문장을 더할 때 `;` 로 끝내면 조용히 앞 문장과 합쳐진다
- **관리자용 화면** — 지금은 REST API 까지다
- ArchUnit 의존성 규칙 테스트 — 규칙 1·2 를 문서가 아닌 빌드로 강제. 게임이 둘 이상 생기면 도입.
  `LedgerEntry.record` 가 `internal` 인 것은 의도 표시일 뿐이다 — 단일 모듈에서 `internal` 은
  애플리케이션 전체를 뜻해 강제력이 없다. 이런 자리를 실제로 못 박는 것이 ArchUnit 의 몫이다
- Micrometer Tracing — `traceId` 를 분산 추적으로 승격
