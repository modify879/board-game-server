# 설계 결정과 근거

설계 결정의 "왜"를 담는다. 규칙 자체는 `CLAUDE.md`·`.claude/rules/` 에 있다.
자동으로 로드되지 않으므로, 결정을 뒤집으려 할 때 읽으면 된다.
규칙을 바꾸기 전에 여기서 그 항목을 먼저 찾아라. 대부분은 이미 한 번 다른 방식으로 시도했다가 되돌린 것이다.

| 주제 | 파일 | 담긴 결정 |
|---|---|---|
| 계층과 포트 | `docs/decisions/layers-and-ports.md` | 계층 이름에 `interfaces` 를 쓰지 않는 이유; 출력 포트가 세 군데로 갈리는 기준; `infrastructure` 가 `application` 을 참조해도 되는 이유; 입력 포트를 명령에만 두는 이유; JPQL 문자열을 쓰지 않는 이유; `common` 을 넷으로 나눈 이유 |
| 예외와 오류 계약 | `docs/decisions/errors.md` | 예외를 계층별로 두고 에러 코드는 컨텍스트당 하나인 이유; `common` 에 범용 예외를 두지 않는 이유 |
| 경계에서의 타입 | `docs/decisions/boundary-types.md` | Command 가 원시 타입만 받는 이유; 그런데 `@RequestParam` 의 도메인 enum 은 그대로 두는 이유; 컨텍스트마다 `UserId` 를 따로 두는 이유 |
| 도메인 모델 | `docs/decisions/domain-model.md` | `private` 생성자 + `of()` 와 일반 생성자 + `init` 이 섞여 있는 이유; 잔액의 진실이 원장인 이유; 한 트랜잭션이 애그리거트 여럿을 고치는 것; 환전을 요청 시점에 차감하는 이유; 관리자 조정에만 멱등 키가 있는 이유; 지갑을 lazy 로 만드는 이유 |
| 동시성 | `docs/decisions/concurrency.md` | 게임 상태는 단일 실행기, 돈은 비관적 락인 이유; 타이머는 상대 시간, 벽시계는 "언제 일어났나" 기록에만; 호출자 타임아웃 대신 DB 에서 상한을 두는 이유; 턴 만료 폴드의 환불을 기립과 같은 트랜잭션에 두는 이유; 테이블을 지우지 않고 닫는 이유; 테이블별 인메모리 상태를 주인이 `TableClosed` 로 치우는 이유 |
| 인증 | `docs/decisions/auth.md` | 역할 변경을 블랙리스트 + 갱신으로 하는 이유; 액세스 토큰과 리프레시 토큰의 형식이 다른 이유; 클라이언트 single-flight 가 서버로 대체되지 않는 이유; 리프레시 토큰을 응답 body 대신 httpOnly 쿠키로 내려주는 이유 |
| 바운디드 컨텍스트 | `docs/decisions/bounded-contexts.md` | `wallet` 이 규칙 1의 대상이 아닌 이유 |
| 홀덤 하우스 룰 | `docs/decisions/holdem-house-rules.md` | 목표 규칙과 근거(차례 시간·바이인·칩 추가·재착석·재시작·쇼다운·히스토리) |

## 아직 하지 않은 것

- ArchUnit 의존성 테스트 — 게임이 둘 이상 생기면 도입. 목표는 BuckPal 과 같은 3줄:
  `application.doesNotDependOn(adapters)` / `domainDoesNotDependOnAdapters()` / `adapters.dontDependOnEachOther()`
- Micrometer Tracing
