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

---

## 아키텍처

헥사고날로 경계를 잡고, 그 안쪽 도메인은 DDD 전술 패턴으로 설계한다.

### 4계층

```
com.jsm.boardgame
├── common/                       # 기술 설정·횡단 관심사만. 도메인 개념 금지
│   ├── config/                   # 스프링 설정 (Security, WebSocket, Jackson)
│   └── support/                  # 공통 예외, 응답 래퍼
│
└── user/                         # ← 모든 바운디드 컨텍스트가 이 형태를 따른다
    ├── domain/
    │   ├── model/                # 애그리거트, 엔티티, VO — 순수 Kotlin
    │   └── repository/           # 출력 포트 (애그리거트를 다룸)
    ├── application/
    │   ├── command/              # UseCase 인터페이스 + 구현 + Command
    │   └── query/                # 조회 서비스 + 조회 출력 포트 + 응답 DTO
    ├── infrastructure/
    │   └── persistence/          # JpaEntity, Spring Data, 매퍼, 어댑터
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
| `user` | 디렉토리만 | 사용자, 닉네임 |
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

조회가 `JpaEntity → 도메인 → 응답 DTO` 로 두 번 매핑되면 안 된다.
JPA 프로젝션으로 응답 DTO 를 바로 만든다.

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

---

## 표준 형태

`user` 컨텍스트를 예로 든 참조 구현. 실제 소스는 아직 없다 — 첫 기능을 만들 때 이 형태를 따른다.

### 1) 도메인 — VO 는 `init` 에서 불변식을 지킨다

```kotlin
// user/domain/model/Nickname.kt
@JvmInline
value class Nickname(val value: String) {
    init {
        require(value.length in 2..20) { "닉네임은 2~20자여야 합니다" }
        require(PATTERN.matches(value)) { "닉네임에 사용할 수 없는 문자가 있습니다" }
    }

    companion object {
        private val PATTERN = Regex("^[가-힣a-zA-Z0-9_]+$")
    }
}

// user/domain/model/UserId.kt
@JvmInline
value class UserId(val value: Long)
```

### 2) 도메인 — 애그리거트와 출력 포트

```kotlin
// user/domain/model/User.kt
class User(
    val id: UserId,
    nickname: Nickname,
) {
    var nickname: Nickname = nickname
        private set

    fun rename(new: Nickname) {
        require(new != nickname) { "기존 닉네임과 동일합니다" }
        nickname = new
    }
}

// user/domain/repository/UserRepository.kt
interface UserRepository {
    fun findById(id: UserId): User?
    fun existsByNickname(nickname: Nickname): Boolean
    fun save(user: User): User
}
```

### 3) 애플리케이션 — 명령은 인터페이스를 둔다

```kotlin
// user/application/command/ChangeNicknameUseCase.kt
interface ChangeNicknameUseCase {
    fun changeNickname(command: ChangeNicknameCommand)
}

data class ChangeNicknameCommand(val userId: Long, val nickname: String)

// user/application/command/ChangeNicknameService.kt
@Service
@Transactional
class ChangeNicknameService(
    private val users: UserRepository,
) : ChangeNicknameUseCase {

    override fun changeNickname(command: ChangeNicknameCommand) {
        val nickname = Nickname(command.nickname)
        require(!users.existsByNickname(nickname)) { "이미 사용 중인 닉네임입니다" }

        val user = users.findById(UserId(command.userId))
            ?: throw NoSuchElementException("사용자를 찾을 수 없습니다")

        user.rename(nickname)
        users.save(user)
    }
}
```

### 4) 애플리케이션 — 조회는 도메인을 거치지 않고, 입력 포트도 없다

```kotlin
// user/application/query/UserSummary.kt
data class UserSummary(val id: Long, val nickname: String)

// user/application/query/UserQueryRepository.kt   ← 출력 포트. 도메인이 아닌 DTO 를 반환한다
interface UserQueryRepository {
    fun findSummaryById(id: Long): UserSummary?
}

// user/application/query/UserQueryService.kt      ← 인터페이스 없음
@Service
@Transactional(readOnly = true)
class UserQueryService(
    private val query: UserQueryRepository,
) {
    fun findSummary(id: Long): UserSummary =
        query.findSummaryById(id) ?: throw NoSuchElementException("사용자를 찾을 수 없습니다")
}
```

### 5) 인프라 — JPA 엔티티, 매퍼, 어댑터

```kotlin
// user/infrastructure/persistence/UserJpaRepository.kt
interface UserJpaRepository : JpaRepository<UserJpaEntity, Long> {
    fun existsByNickname(nickname: String): Boolean

    // 조회 전용 프로젝션. 도메인을 거치지 않고 응답 DTO 를 바로 만든다.
    @Query("select new com.jsm.boardgame.user.application.query.UserSummary(u.id, u.nickname) from UserJpaEntity u where u.id = :id")
    fun findSummaryById(id: Long): UserSummary?
}

// user/infrastructure/persistence/UserJpaEntity.kt
@Entity
@Table(name = "users")
class UserJpaEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(length = 20, nullable = false, unique = true)
    var nickname: String,
)

// user/infrastructure/persistence/UserMapper.kt
fun UserJpaEntity.toDomain() = User(UserId(id), Nickname(nickname))
fun User.toJpaEntity() = UserJpaEntity(id.value, nickname.value)

// user/infrastructure/persistence/UserRepositoryAdapter.kt
@Repository
class UserRepositoryAdapter(
    private val jpa: UserJpaRepository,
) : UserRepository {

    override fun findById(id: UserId): User? =
        jpa.findById(id.value).orElse(null)?.toDomain()

    override fun existsByNickname(nickname: Nickname): Boolean =
        jpa.existsByNickname(nickname.value)

    override fun save(user: User): User =
        jpa.save(user.toJpaEntity()).toDomain()
}

// user/infrastructure/persistence/UserQueryRepositoryAdapter.kt
@Repository
class UserQueryRepositoryAdapter(
    private val jpa: UserJpaRepository,
) : UserQueryRepository {

    // JpaEntity → 도메인 → DTO 로 두 번 매핑하지 않고 바로 프로젝션한다
    override fun findSummaryById(id: Long): UserSummary? = jpa.findSummaryById(id)
}
```

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

- `user` 컨텍스트 실제 구현
- 게임 선정 및 첫 게임 컨텍스트, 방/좌석 컨텍스트
- 인증/인가 (Spring Security 설정은 의존성만 들어가 있다)
- **Flyway 마이그레이션** — 지금은 `ddl-auto: update`. 운영 배포 전 반드시 전환한다
- ArchUnit 의존성 규칙 테스트 — 규칙 1·2 를 문서가 아닌 빌드로 강제. 게임이 둘 이상 생기면 도입
- Testcontainers 기반 통합 테스트
