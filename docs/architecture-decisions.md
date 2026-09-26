# 설계 결정과 근거

`CLAUDE.md` 는 **어기면 깨지는 규칙**만 담는다(공식 권고: 200줄 이하. 길수록 컨텍스트를 더 쓰고 지시를 덜 따른다).
이 문서는 그 규칙들이 **왜** 그렇게 정해졌는지를 담는다. 자동으로 로드되지 않으므로,
결정을 뒤집으려 할 때 읽으면 된다.

> 규칙을 바꾸기 전에 여기서 그 항목을 먼저 찾아라. 대부분은 이미 한 번 다른 방식으로
> 시도했다가 되돌린 것이다.

---

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

### 명령/조회 절단면이 역할 분리보다 위인 이유
BuckPal 은 `port/in` + `service` 를 최상위에 두지만 거기엔 명령/조회 분리가 없다.
이 프로젝트는 규칙 3이 먼저이므로 `command/{usecase,service}` 가 맞다.

### 입력 포트를 명령에만 두는 이유
의존성 역전이 필요한 건 **나가는** 방향뿐이다. `presentation → application` 은 이미 올바른
방향이라 뒤집을 게 없다. 조회는 계약을 역전할 이유가 없으므로 클래스 하나로 끝낸다.

### JPQL 문자열을 쓰지 않는 이유
조건이 선택적인 쿼리(검색·필터·랭킹)가 생기면 문자열을 이어붙이거나 쿼리를 여러 벌 두게 되고,
필드 이름이 바뀌어도 컴파일이 통과한다. 단건 조회에서 JDSL 이 더 장황한 것은 규칙을 하나로
유지하는 값이다.

### `common` 을 셋으로 나눈 이유
가장 중요한 경계는 `error` 와 `web` 사이다 — `error` 는 `domain` 이 import 하는 유일한 common
패키지이고 `web` 은 `domain` 이 절대 참조하지 않는다. 한 패키지에 있으면 이 선이 보이지 않는다.
`ConstraintViolations` 는 오류 응답 경로가 아니라 어댑터가 쓰는 파싱 유틸이라 `persistence` 다.

---

## 예외와 오류 계약

### 예외를 계층별로 두고 에러 코드는 컨텍스트당 하나인 이유
도메인 불변식 위반이 domain 예외라는 데는 문헌상 이견이 없다
(MS Learn: *"invariants enforcement is the responsibility of the domain entities"*).
`presentation` 이 자기 예외를 갖는 것도 같다 — *"that model is meant to be a ViewModel or DTO
and that's an MVC or API concern **not a domain model concern**."*

반면 에러 코드까지 계층별로 쪼개면 **클라이언트가 보는 계약이 패키지 구조를 따라 흔들린다.**
예외를 한 계층 옮기는 리팩터링이 API 변경이 되어선 안 된다.

`XxxNotFoundException` 을 domain 에 두는 것은 **문헌이 실제로 갈리는 지점**이라 그대로 둔다
("존재하지 않음"이 도메인 불변식인지 유스케이스 관심사인지 합의가 없다).

### 도메인이 `HttpStatus` 를 모르는 이유
> "Application's core and domain layers shouldn't throw HTTP exceptions or statuses since it
> shouldn't know in what context it's used." — Sairyss/domain-driven-hexagon

`ErrorKind`(INVALID/UNAUTHORIZED/FORBIDDEN/NOT_FOUND/CONFLICT)가 그 절충 지점이고,
HTTP 매핑은 핸들러 한 곳에서만 한다.

### `common` 에 범용 예외를 두지 않는 이유
타입이 아니라 메시지 문자열이 의미를 나르게 되어 아이디 중복인지 닉네임 중복인지 구분할 수 없다.
기반 타입(`BusinessException`)을 공유하는 것은 표준 패턴이므로 문제가 아니다.

---

## 경계에서의 타입

### Command 가 원시 타입만 받는 이유
`application` 이 계약이므로 여기서 도메인 타입을 받으면 **도메인 enum 상수명이 곧 API 계약**이
되고, 이름을 다듬는 리팩터링이 클라이언트를 깨뜨린다. 변환이 서비스로 오면 알 수 없는 값도
`USER_ROLE_INVALID` 로 규칙 8 을 따라 나간다 — 잭슨 역직렬화에서 걸리면 `errorCode` 없는 응답이 된다.

### 그런데 `@RequestParam` 의 도메인 enum 은 그대로 두는 이유
업계 통설은 enum 이 **출력**에서 위험하고 입력에서는 상대적으로 안전하다는 것이다
(tyk.io: *"Assume that adding or removing enum values in API responses will break API client code."*).
이 저장소는 이미 위험한 쪽만 막아 뒀다 — `DepositRequestView.status` 는 `String` 이다.

Hombergs 도 매핑 전략을 코드베이스 전체에 하나로 강제하지 말라고 못 박는다 —
*"you should resist defining a single strategy as a hard-and-fast global rule for the whole
codebase... the answer is the typical 'it depends'."*
**`presentation` 은 지점별 판단이 허용되고 `application` 계약은 아니다.** 이 비대칭이 의도다.

---

## 도메인 모델

### `reconstitute()` 가 검증하지 않는 이유
`of()` 로 복원하면 **입력 검증 규칙을 이미 저장된 데이터에 다시 적용**하게 된다.
닉네임 상한을 20자에서 12자로 줄이면 15자 닉네임을 가진 기존 사용자가 로그인조차 못 하고,
원인은 인증 코드가 아니라 매퍼에 있어 찾기도 어렵다. 도메인은 자기 저장소에서 나온 값을 신뢰한다.

### `private` 생성자 + `of()` 와 일반 생성자 + `init` 이 섞여 있는 이유
value class 는 `init` 에서 값을 바꿀 수 없어 정규화를 할 수 없다. 정규화가 필요 없는
VO(`PasswordHash`)는 일반 생성자 + `init` 검증을 쓴다. 이 비대칭은 의도된 것이다.

### 잔액의 진실이 원장인 이유
`Wallet.balance` 는 빠른 조회를 위한 캐시이고 append-only 인 `LedgerEntry` 가 진실이다.
엔트리에 `balanceAfter` 를 남겨 원장만 훑어도 잔액을 검증할 수 있다.
`credit`/`debit` 로 가르지 않은 것은 **잔액만 바꾸고 원장을 빠뜨리는 호출이 존재할 수 없게**
하기 위해서다 — `record()` 의 반환값이 곧 저장해야 할 엔트리다.

부호를 `LedgerEntryType.direction` 이 나르는 이유: `Money` 가 음수를 못 갖는다.
관리자 조정이 `ADMIN_ADJUSTMENT_CREDIT`/`_DEBIT` 두 상수로 갈라진 것도 같은 이유이고,
하나로 합치면 부호를 따로 실어야 해서 원장 합산으로 잔액을 검증할 수 없게 된다.

이중기입은 쓰지 않는다. 대신 `referenceType`/`referenceId` 로 모든 엔트리가 출처를 가리킨다.

### 한 트랜잭션이 애그리거트 여럿을 고치는 것
Vernon 의 "한 트랜잭션에 애그리거트 하나" 를 지키지 않는다. `ApproveDepositRequestService` 는
셋을 고친다. `Wallet`+`LedgerEntry` 2개는 **구조적으로 피할 수 없다** — 원장은 무한히 늘어나는
컬렉션이라 애그리거트 안에 넣을 수 없고, `balance` 를 지우고 매번 SUM 하면 조회 비용이 폭증한다.
세 번째를 떼어내려면 아웃박스+이벤트+정산 대조가 필요하고 "승인됐는데 잔액은 그대로"인 창이 생긴다.
**Vernon 의 원칙은 확장성과 경합을 위한 것이지 정확성을 위한 것이 아니다.**

### 환전을 요청 시점에 차감하는 이유
요청만 걸어두고 차감을 승인 시점으로 미루면, 요청 후 게임에서 다 잃은 뒤 승인되어 잔액이 음수가 된다.
요청 시 즉시 차감하고 `WITHDRAWAL_HOLD` 를 남긴다. 반려·취소는 `WITHDRAWAL_REFUND` 로 환급하고
승인은 상태만 바꾼다. 이중 환급은 도메인 상태 전이와 `@Version` 두 겹으로 막고, **둘 다 테스트한다**.

### 남의 리소스가 404 인 이유
403 과 404 가 갈리면 인증된 사용자가 아무 id 나 넣어보는 것만으로 남의 요청이 존재하는지
열거할 수 있다. 도메인의 소유자 검사(`DepositRequest.cancel` 의 `NOT_REQUEST_OWNER`)를 남기는 것은
서비스가 먼저 걸러서 HTTP 로 안 나올 뿐, 애그리거트가 자기 소유권을 안 지키게 두면 안 되기 때문이다.
`WalletApiIntegrationTest` 가 **남의 요청과 없는 요청이 같은 응답인지** 검증한다 —
한쪽만 검증하면 이 규칙은 다시 뚫린다.

### 지갑을 lazy 로 만드는 이유
회원가입이 `wallet` 을 부르면 컨텍스트가 결합된다. `user` 는 `wallet` 을 전혀 모른다.
조회 경로에서 만들지 않는 것은 조회가 상태를 바꾸면 규칙 3이 깨지기 때문이다.

---

## 인증

### 역할 변경을 블랙리스트 + 갱신으로 하는 이유
강등 반영은 재로그인 강제와 똑같이 즉시인데 사용자는 로그아웃되지 않고, 클라이언트 코드는
한 줄도 바뀌지 않는다(이미 타는 401 → refresh 경로를 그대로 쓴다).

대가는 `RefreshTokenService` 가 갱신마다 DB 로 역할을 읽는 것이다. 역할을 Redis 세션 문자열에
끼워 넣으면 DB 를 안 타지만 `RedisAuthSessionStore` 의 직렬화 포맷과 Lua 스크립트를 건드려야
한다 — **이미 두 번 버그가 난 자리다.**

**닫히지 않은 경합이 하나 있다**: 역할 변경 트랜잭션이 커밋되기 전에 들어온 갱신 요청은 옛 역할이
박힌 토큰을 받고, 그 jti 는 블랙리스트에 잡히지 않는다. 강등이 최대 `access-token-ttl` 만큼 늦어진다.
두 문장의 순서를 어떻게 바꿔도 닫히지 않는다 — "이 시각 이전 발급분 전부 무효" 라는 기준이 필요하다.

**최초 관리자만 DB 로 직접 만드는 이유**: DB 직접 `UPDATE` 로는 토큰을 죽일 수 없어 즉시 강등이
불가능하다. 최초 관리자는 아직 세션이 없어 죽일 토큰도 없으므로 예외가 된다.

### 액세스 토큰과 리프레시 토큰의 형식이 다른 이유
**형식이 다르다는 것 자체가 방어선이다.** 같은 키로 서명된 같은 구조가 되는 순간 둘을 가르는 것은
클레임 한 칸뿐이고, 확인하는 자리를 한 군데만 빠뜨려도 리프레시 토큰이 액세스 토큰으로 통한다.
**"왜 둘이 다르냐, 통일하자" 가 개선처럼 보이는 것이 이 결정의 위험한 점이다.**

리프레시 토큰에서 userId 를 유도하지 않는 이유: `/api/auth/refresh` 는 `permitAll` 이고 회전
불일치의 반응이 "세션 전체 폐기" 라서, **숫자만 아는 사람이 아무 문자열이나 보내 남을 로그아웃시킬
수 있다.** 인덱스를 회전 때 지우지 않는 이유: 지우면 탈취된 옛 토큰이 "모르는 토큰" 으로 조용히
떨어져 재사용 탐지가 무력화된다.

### 클라이언트 single-flight 가 서버로 대체되지 않는 이유
화면 하나에서 요청 여러 개가 동시에 401 을 받으면 각자 갱신을 시도하는데, 서버는 그게 "같은 의도의
중복"인지 "진짜 여러 번의 시도"인지 알 방법이 없다 — 클라이언트만 안다. 서버는 이미 두 겹을 갖췄다
(유예 창, 즉시 경합 가드). 업계 권고가 둘을 함께 쓰는 것이다.

### 리프레시 토큰을 응답 body 대신 httpOnly 쿠키로 내려주는 이유
지금까지는 리프레시 토큰을 JSON body 로 내려 클라이언트가 디스크에 평문으로 저장했다. XSS 한 번이면
14일짜리 토큰이 그대로 유출된다. `httpOnly` 쿠키는 JS 가 아예 읽을 수 없어, 같은 취약점이 있어도
이 토큰만은 새지 않는다.

**CSRF 설정은 그대로 disabled 로 둔다.** 쿠키가 자동으로 붙는 위험은 세 겹으로 막혀 있다:
`SameSite=Strict` 라 다른 사이트에서 보낸 요청에는 쿠키 자체가 붙지 않고, 쿠키를 쓰는 경로가
`/api/auth/refresh` 하나뿐이라 공격자가 뭘 하든 새 토큰 한 쌍을 받아가는 것 말고는 할 게 없고,
그마저도 서버에 CORS 설정이 없어 동일 출처 정책상 다른 origin 은 응답을 읽지 못한다. CSRF 가 위험한 이유는 "브라우저가
인증을 대신 붙여준 상태로 상태를 바꾸는 요청을 실행시킬 수 있다"는 것인데, 여기서는 상태를 바꾸는
건 맞지만 결과(새 토큰)를 공격자가 가져갈 방법이 없다.

**`Path=/api/auth` 로 좁히는 이유**: 쿠키 범위를 안 좁히면 이 쿠키가 다른 모든 API 호출과
WebSocket 연결에도 매번 실려 간다 — 안 쓰는 요청에 14일짜리 민감한 값을 계속 얹을 이유가 없다.

**`Secure` 의 한계**: `localhost` 는 예외로 취급돼 로컬 개발에서는 문제없이 저장된다. 같은 LAN 의
다른 기기에서 평문 HTTP(`http://192.168.x.x`)로 접속하면 브라우저가 쿠키 저장을 거부한다 — 그런
테스트 환경이 필요해지면 그때 가서 속성을 설정으로 뺀다.

---

## 바운디드 컨텍스트

### `wallet` 이 규칙 1의 대상이 아닌 이유
규칙 1이 금지하는 것은 `GameRule`/`Move` 같은 **게임 규칙 상위 타입**이지 비게임 컨텍스트가 아니다.
지갑 잔액은 게임이 끝나도 남아 나중에 환전되는 **정산 워크플로**이고, 게임 안의 재화
(홀덤의 `Chips`)는 그 게임이 자기 VO 로 갖는다.

### 같은 이름의 클래스가 두 게임에 있는 것
중복이 아니다. 같은 단어가 컨텍스트마다 다른 뜻을 갖는 것이 바운디드 컨텍스트의 정의이며,
유비쿼터스 언어는 컨텍스트 안에서만 통한다.

---

## 인프라

### `ddl-auto: update` 를 계속 쓰는 이유
Flyway 로 전환할 계획이 없다. 대가는 컬럼 삭제·이름 변경·타입 변경·데이터 이관을 손으로 해야
한다는 것이다. 제약을 `data.sql` 의 멱등 DDL 로 거는 이유는, 손으로 한 번 실행하면
Testcontainers 의 빈 DB 에 제약이 없어 **번역 경로가 검증되지 않기** 때문이다.

### 로그인 타이밍 방어를 제거한 이유
없는 아이디는 BCrypt 를 타지 않아 응답이 짧다(평균 77ms vs 3.6ms). 시간을 맞추던 더미 해시
방어는 `3e4d64e` 에서 **의도적으로** 제거했다 — 모르고 빠진 게 아니다.
되살리려면 위협 모델부터 정해라.

### `internal` 이 강제력이 없는 이유
단일 모듈에서 `internal` 은 애플리케이션 전체를 뜻한다. `LedgerEntry.record` 의 `internal` 은
의도 표시일 뿐이고, 실제로 못 박는 것은 ArchUnit 의 몫이다.
