# board-game

여러 종류의 게임을 올릴 실시간 멀티플레이 서버.

Kotlin 2.3 / Spring Boot 4.1 / Java 25 / PostgreSQL / 단일 Gradle 모듈.

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

테스트는 Testcontainers 로 자기 컨테이너를 띄운다. `compose.yaml` 과 무관하며 역시 Docker 가 필요하다.
도메인 테스트는 스프링도 컨테이너도 없이 돈다 — 그게 도메인을 분리해서 얻는 것이다.

---

## 아키텍처

헥사고날로 경계를 잡고, 그 안쪽 도메인은 DDD 전술 패턴으로 설계한다.

### 4계층

```
com.jsm.boardgame
├── common/                       # 기술 설정·횡단 관심사만. 도메인 개념 금지
│   ├── config/                   # 스프링 설정 (Security, WebSocket, Jackson)
│   └── support/                  # 오류 계약(ErrorCode/ErrorKind), 전역 예외 핸들러, traceId 필터
│
└── user/                         # ← 모든 바운디드 컨텍스트가 이 형태를 따른다
    ├── domain/
    │   ├── model/                # 애그리거트, 엔티티, VO — 순수 Kotlin
    │   ├── repository/           # 출력 포트 (애그리거트를 다룸)
    │   ├── service/              # 도메인이 필요로 하지만 스스로 구현 못 하는 출력 포트
    │   │                         #   (해싱, 셔플, 주사위, 시계)
    │   └── exception/            # 이 컨텍스트의 에러 코드와 도메인 예외
    ├── application/
    │   ├── command/              # UseCase 인터페이스 + 구현 + Command
    │   └── query/                # 조회 서비스 + 조회 출력 포트 + 응답 DTO
    ├── infrastructure/
    │   ├── persistence/          # JpaEntity, Spring Data, 매퍼, 어댑터
    │   └── security/             # 해싱 등 보안 관련 어댑터
    └── presentation/
        ├── rest/                 # Controller + Request/Response
        └── ws/                   # WebSocket 핸들러
```

계층 이름은 `domain` / `application` / `infrastructure` / `presentation` 으로 통일한다.
`interfaces` 는 쓰지 않는다 — Kotlin 의 `interface` 키워드와 시각적으로 충돌하는데,
이 프로젝트는 포트 인터페이스를 `domain/repository` 와 `application` 에 두므로 혼동이 크다.

헥사고날 대응: `presentation` = driving adapter, `infrastructure` = driven adapter,
`domain/repository` 와 `application/query` 의 포트 = output port, `application/command` 의 UseCase = input port.

### 의존성 방향

```
presentation → application → domain ← infrastructure
```

- `domain` 은 아무것도 의존하지 않는다.
- `application` 과 `domain` 은 `infrastructure` 를 참조하지 않는다. 예외 없다.
- 바운디드 컨텍스트끼리 서로의 `domain` 을 참조하지 않는다.

---

## 바운디드 컨텍스트

| 컨텍스트 | 상태 | 책임 |
|---|---|---|
| `user` | 회원가입·프로필 조회 구현됨 | 사용자, 아이디, 비밀번호, 닉네임, 프로필 이미지 |
| 각 게임 | 미정 | 그 게임의 규칙 전부 |
| 방/좌석 | 미정 | 세션 수명주기. 좌석 모델이 게임에 좌우되므로 첫 게임과 함께 설계 |

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

조회가 `JpaEntity → 도메인 → 응답 DTO` 로 두 번 매핑되면 안 된다.
생성자 프로젝션으로 응답 DTO 를 바로 만든다.

**`application/query` 경로의 읽기는 Kotlin JDSL 로 작성한다.**
"모든 DB 접근"이 아니라 조회 경로만이다 — 명령 경로에서 애그리거트를 불러오거나
`existsBy...` 로 사전 확인하는 것은 Spring Data 파생 쿼리로 충분하다.
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

### 6. 히든 정보는 뷰어별로 마스킹한다

상대 패가 보이면 안 되는 게임에서 이게 무너지면 게임이 성립하지 않는다. 보안 규칙으로 다룬다.

- **WebSocket 으로 도메인 객체나 전체 게임 상태를 그대로 내보내지 않는다.**
- 반드시 `game.viewFor(viewer: PlayerId): XxxView` 를 거쳐 관찰자 시점으로 변환한 뒤 전송한다.
- 브로드캐스트의 기본값은 "전원에게 같은 메시지"가 아니다. **좌석별로 다른 페이로드**가 기본이다.

### 7. 재화는 원시 타입으로 다루지 않는다

베팅액·자산은 각 게임 컨텍스트가 자기 VO 로 갖는다. `Int`/`Long` 으로 다루지 않는다.
음수 방지와 연산 캡슐화가 목적이다. 게임 간에 이 타입을 공유하지 않는다.

### 8. 오류는 코드로 계약하고, 문구는 클라이언트가 만든다

구현은 `common/support/` 와 각 컨텍스트의 `domain/exception/` 을 참조한다.
여기에는 코드만 봐서는 되돌리기 쉬운 결정의 이유만 적는다.

- 예외는 규칙을 소유한 컨텍스트가 소유한다. `common` 에 범용 예외를 두지 않는다
  — 타입이 아니라 메시지 문자열이 의미를 나르게 되어 아이디 중복인지 닉네임 중복인지 구분할 수 없다
- `ErrorCode` 는 `HttpStatus` 를 모른다. 도메인이 참조하는 타입이라 스프링이 들어오면 규칙 2가 깨진다.
  도메인은 `ErrorKind`(INVALID/CONFLICT/NOT_FOUND/FORBIDDEN)까지만 알고, 상태 매핑은 핸들러가 한다
- 응답에는 `errorCode`, 로그에는 `logMessage`. 둘은 `traceId` 로 잇는다
  — 분리만 하고 잇지 않으면 사용자 신고를 받아도 어느 로그인지 찾을 수 없다
- 서버는 사용자 문구를 내려보내지 않는다. `detail` 은 **비어 있는 게 정상**이다.
  `type`/`title` 도 스프링 기본값 그대로 둔다
- 4xx 는 WARN 에 스택 없이, 5xx 는 ERROR 에 스택 포함
  — 중복 가입 시도마다 스택이 찍히면 로그가 쓸모없어진다
- 로그 메시지에 비밀번호를 남기지 않는다. `RawPassword`·`PasswordHash` 는 `toString()` 이 마스킹되어 있다

---

## 표준 형태

`user` 컨텍스트가 참조 구현이다. 새 컨텍스트는 그 파일 배치를 그대로 따른다.
여기에는 **코드를 봐도 의도가 드러나지 않는 것**만 적는다.

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

`of()` 는 **사용자 입력용**이고, 영속 계층에서 복원할 때는 `restore()` 를 쓴다.
`restore()` 는 검증도 정규화도 하지 않는다 — **빠뜨린 게 아니라 의도한 것이다.**

복원할 때 `of()` 를 쓰면 **입력 검증 규칙을 이미 저장된 데이터에 다시 적용**하게 되고,
규칙을 조이는 순간 기존 데이터를 읽을 수 없게 된다. 닉네임 상한을 20자에서 12자로 줄이면
15자 닉네임을 가진 기존 사용자가 로그인조차 못 하고, 원인은 인증 코드가 아니라 매퍼에 있어 찾기도 어렵다.
도메인은 자기 저장소에서 나온 값을 신뢰한다.

판단할 것이 없는 기계적인 규칙이다 — **매퍼는 `restore()`, 그 외 전부 `of()`.**
애그리거트도 같은 이름을 쓴다(`User.restore()`).
규칙을 조인 뒤 기존 데이터를 정리해야 한다면 그건 별도의 마이그레이션 작업이지 읽기 경로가 할 일이 아니다.

### 애그리거트의 식별자는 nullable 이다

`val id: UserId?` 에서 `null` 은 아직 저장되지 않았다는 뜻이다.
`UserId(0)` 같은 센티넬을 쓰면 JPA 의 관례를 도메인이 물려받는 것이다.
신규 생성은 `register()`, 영속 계층에서의 복원은 `reconstitute()` 로 의도를 갈라 놓는다.

### 유일성은 DB 가 보장한다

응용 계층의 `existsBy...` 사전 체크는 친절한 오류 응답을 위한 것이고 **동시 요청을 막지 못한다.**
**이름을 붙인** unique 제약(`uk_users_username`)이 실제 보장이다 — 이름이 있어야 어댑터가 어느 제약이
깨졌는지 구분해 도메인 예외로 변환할 수 있다. 스프링 예외가 `application` 까지 올라가면 의존성 방향이 깨진다.

### 문자열 컬럼은 `text` 다

PostgreSQL 에서 `text` 와 `varchar(n)` 은 성능이 같다.
대신 길이 제약이 DB 에서 사라지므로 **VO 가 유일한 방어선**이 된다.

### 외부 리소스는 키만 저장하고 URL 은 presentation 이 조립한다

프로필 이미지는 `profile_image_key` 만 저장한다(`null` = 기본 이미지).
CDN 도메인은 인프라 설정이라 도메인 모델이 알면 안 되고, 버킷이나 환경이 바뀌어도 DB 를 건드리지 않는다.

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

- **인증/인가** — JWT 를 Authorization 헤더로, Redis 에 리프레시 토큰과 로그아웃 블랙리스트.
  `SecurityConfig` 는 이미 무상태로 잡혀 있고 **인가 규칙만 임시로 전면 허용** 상태다.
  이때 `PasswordHasher.matches()` 와 `UserRepository.findByUsername()` 이 추가된다
  — BCrypt 는 해시에 솔트가 들어 있어 `==` 비교가 성립하지 않는다
- 게임 선정 및 첫 게임 컨텍스트, 방/좌석 컨텍스트
- 프로필 이미지 업로드 (스토리지 연동, presigned URL). 지금은 키를 저장할 자리만 있다
- 닉네임·비밀번호 변경, 회원 탈퇴
- **Flyway 마이그레이션** — 지금은 `ddl-auto: update`. 운영 배포 전 반드시 전환한다
- ArchUnit 의존성 규칙 테스트 — 규칙 1·2 를 문서가 아닌 빌드로 강제. 게임이 둘 이상 생기면 도입
- Micrometer Tracing — `traceId` 를 분산 추적으로 승격
