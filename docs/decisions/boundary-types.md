## 경계에서의 타입

### Command 가 원시 타입만 받는 이유
`application` 이 계약이므로 여기서 도메인 타입을 받으면 **도메인 enum 상수명이 곧 API 계약**이
되고, 이름을 다듬는 리팩터링이 클라이언트를 깨뜨린다. 변환이 서비스로 오면 알 수 없는 값도
`USER_ROLE_INVALID` 로 규칙 8 을 따라 나간다 — 잭슨 역직렬화에서 걸리면 `errorCode` 없는 응답이 된다.

### 그런데 `@RequestParam` 의 도메인 enum 은 그대로 두는 이유
입력에서는 상대적으로 안전하고 **출력**에서 위험하다는 것이 통설이다(tyk.io).
위험한 쪽은 이미 막았다 — `DepositRequestView.status` 는 `String` 이다.

**`presentation` 은 지점별 판단이 허용되고 `application` 계약은 아니다.** 이 비대칭이 의도다(Hombergs).

### 컨텍스트마다 `UserId` 를 따로 두는 이유
`user` 의 `UserId` 를 공유하는 공유 커널은 규칙 1 위반이고 Common 창고가 되기 쉽다. 반면 원시 `Long` 은 userId 와 tableId 를
바꿔 넣어도 컴파일러가 못 잡는다. Wlaschin "Designing with types"(감싸서 쓰고 경계에서 푼다)·jMolecules `Identifier` 와 같은 방향이다.
경계(Command·DTO·JPA·조회·컨텍스트 간 이벤트)는 원시 타입으로 둔다 — 아래 절과 같은 이유다.


## value class 는 경계에서도 동작한다 — 그래도 경계는 원시 타입

검증일 2026-10 · Spring Boot 4.1 / Jackson 3.1.5 + jackson-module-kotlin / Hibernate 7

- `@PathVariable`·`@RequestParam` 이 `@JvmInline value class` 로 바인딩된다.
- Jackson 3 + kotlin 모듈이 요청 본문을 역직렬화하고, 응답을 `{"id":1}` 처럼 감싸지 않고 직렬화한다 (`ValueClassBindingIntegrationTest` 가 못 박는다).
- Hibernate 7 은 value class 필드를 필드 접근으로 bigint 컬럼에 저장·조회한다. 단 주 생성자 파라미터(기본값 포함)로 두면 `InstantiationException: No default constructor` — 본문 프로퍼티로 둬야 한다. 프로퍼티 접근은 getter 이름이 맹글링되어 검증하지 않았다.
- 그래도 경계(Command·DTO·JPA 엔티티)는 원시 타입으로 둔다: 규칙 2·3 과 Command 규칙 때문이며, 기술적 제약 때문이 아니다.
