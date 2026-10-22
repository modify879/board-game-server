## 계층과 포트

### 계층 이름에 `interfaces` 를 쓰지 않는 이유
Kotlin 의 `interface` 키워드와 시각적으로 충돌한다. 이 프로젝트는 포트 인터페이스를
`domain` 과 `application` 양쪽에 두므로 혼동이 특히 크다.

### 출력 포트가 세 군데로 갈리는 기준
| 위치 | 기준 | 예 |
|---|---|---|
| `domain/repository` | 애그리거트를 저장·조회 | `WalletRepository` |
| `domain/service` | **도메인 규칙**이 필요로 하는데 스스로 못 함 | `PasswordHasher`, `Shuffler` |
| `application/port` | **유스케이스**가 필요로 함. 도메인은 모름 | `AuthSessionStore`, `UserExistence` |

판별 질문은 하나다 — **"`User.kt` 안에 이 단어가 나오나?"** `passwordHash` 는 나오고
`session`·`token` 은 안 나온다.

DDD 전통(Evans)은 Repository 를 domain 에, BuckPal 은 모든 출력 포트를 `application` 에 둔다. 이 배치는 그 **절충안**이다.

### `infrastructure` 가 `application` 을 참조해도 되는 이유
의존성 규칙이 금지하는 것은 **안쪽이 바깥쪽을 아는 것** 하나뿐이다.

> "source code dependencies can only point inwards. Nothing in an inner circle can know
> anything at all about something in an outer circle." — Uncle Bob, *The Clean Architecture*

`presentation` 과 `infrastructure` 는 둘 다 바깥 원이다. 어댑터가 자기가 구현하는 포트를
import 하는 것은 규칙 위반이 아니라 **의존성 역전이 작동하는 방식 자체**다.
BuckPal 의 ArchUnit 규칙이 검사하는 것도 `applicationLayer.doesNotDependOn(adapters)`,
`domainDoesNotDependOnAdapters()`, `adapters.dontDependOnEachOther()` 셋뿐이고
`adapter → application` 을 금지하는 규칙은 **없다**.

이 저장소가 포트를 종류별로 나눠 두므로 어댑터의 참조 대상도 양쪽으로 갈린다.
**포트를 어디 두느냐가 어댑터가 무엇을 import 하는지를 정한다.**

### 입력 포트를 명령에만 두는 이유
의존성 역전이 필요한 건 **나가는** 방향뿐이다. `presentation → application` 은 이미 올바른
방향이라 뒤집을 게 없다. 조회는 계약을 역전할 이유가 없으므로 클래스 하나로 끝낸다.

### JPQL 문자열을 쓰지 않는 이유
조건이 선택적인 쿼리(검색·필터·랭킹)가 생기면 문자열을 이어붙이거나 쿼리를 여러 벌 두게 되고,
필드 이름이 바뀌어도 컴파일이 통과한다. 단건 조회에서 JDSL 이 더 장황한 것은 규칙을 하나로
유지하는 값이다.

**예외**: `@Lock(PESSIMISTIC_WRITE)` 단건 조회(`WalletJpaEntity.findByUserIdForUpdate` 등)는 조건이 하나뿐이라 문제가 생기지 않는다.

### `common` 을 넷으로 나눈 이유
가장 중요한 경계는 `error` 와 `web` 사이다 — `error` 는 `domain` 이 import 하는 유일한 common
패키지이고 `web` 은 `domain` 이 절대 참조하지 않는다. 한 패키지에 있으면 이 선이 보이지 않는다.
`ConstraintViolations` 는 오류 응답 경로가 아니라 어댑터가 쓰는 파싱 유틸이라 `persistence` 다.
`config` 는 어느 컨텍스트에도 속하지 않는 빈 설정(`Clock`·보안 필터 체인·WebSocket·Kotlin JDSL·STOMP 세션 스케줄러·QA 프로필 가드)이다.
