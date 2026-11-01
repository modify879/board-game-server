## 도메인 모델

### `wallet` 이 규칙 1의 대상이 아닌 이유
규칙 1이 금지하는 것은 `GameRule`/`Move` 같은 **게임 규칙 상위 타입**이지 비게임 컨텍스트가 아니다.
지갑 잔액은 게임이 끝나도 남아 나중에 환전되는 **정산 워크플로**이고, 게임 안의 재화
(홀덤의 `Chips`)는 그 게임이 자기 VO 로 갖는다.

### `private` 생성자 + `of()` 와 일반 생성자 + `init` 이 섞여 있는 이유
value class 는 `init` 에서 값을 바꿀 수 없어 정규화를 할 수 없다. 정규화가 필요 없는
VO(`PasswordHash`)는 일반 생성자 + `init` 검증을 쓴다. 이 비대칭은 의도된 것이다.

### 잔액의 진실이 원장인 이유
`Wallet.balance` 는 빠른 조회를 위한 캐시이고 append-only 인 `LedgerEntry` 가 진실이다.
`balanceAfter` 를 남겨 원장만 훑어도 잔액을 검증할 수 있다. 이중기입은 쓰지 않는다 —
대신 `referenceType`/`referenceId` 로 모든 엔트리가 출처를 가리킨다.

### 한 트랜잭션이 애그리거트 여럿을 고치는 것
Vernon 의 "한 트랜잭션에 애그리거트 하나" 를 지키지 않는다. `ApproveDepositRequestService` 는
셋을 고친다. 환전 요청·반려·취소, 게임 이체(홀덤 트랜잭션에 합류), `HandSettler`·`StandUpService`·`CancelHandService`(테이블+핸드+지갑)도 같은 이유로 여럿을 고친다. `Wallet`+`LedgerEntry` 2개는 **구조적으로 피할 수 없다** — 원장은 무한히 늘어나는
컬렉션이라 애그리거트 안에 넣을 수 없고, `balance` 를 지우고 매번 SUM 하면 조회 비용이 폭증한다.
세 번째를 떼어내려면 아웃박스+이벤트+정산 대조가 필요하고 "승인됐는데 잔액은 그대로"인 창이 생긴다.
**Vernon 의 원칙은 확장성과 경합을 위한 것이지 정확성을 위한 것이 아니다.**

### 환전을 요청 시점에 차감하는 이유
요청만 걸어두고 차감을 승인 시점으로 미루면, 요청 후 게임에서 다 잃은 뒤 승인되어 잔액이 음수가 된다.
이중 환급은 도메인 상태 전이와 비관적 락 두 겹으로 막고, **둘 다 테스트한다**.

### 관리자 조정에만 멱등 키가 있는 이유
충전·환전 요청도 클라이언트 재시도용 멱등 키(`uk_*_user_key`)를 갖지만, 승인은 상태 전이와 비관적 락으로
이중 처리를 막는다. 관리자 조정(`AdjustWalletBalanceService`)은 상태 기계 없이 바로 잔액을 바꾸므로, 커밋 후 타임아웃으로
재시도되면 이중 반영을 막을 방법이 없다 — 그래서 멱등 키가 유일한 방어인 곳은 여기뿐이다.

### 지갑을 lazy 로 만드는 이유
회원가입이 `wallet` 을 부르면 컨텍스트가 결합된다. 회원가입은 `wallet` 을 부르지 않는다(`user` 는 탈퇴 선행 조건 확인용 `WalletHoldings` 포트와 `infrastructure/acl` 어댑터로만 안다).
조회 경로에서 만들지 않는 것은 조회가 상태를 바꾸면 규칙 3이 깨지기 때문이다.

### `HoldemTable` 의 일부 불변식을 호출자가 지키는 이유
`addChips`·`applyChipsReservation`·`close` 는 "핸드가 진행 중이 아닐 때만" 불러야 하는데 `HoldemTable` 은 이를 검사하지 않는다.
핸드 상태는 다른 애그리거트(`Hand`, `HandStore`)에 있어서 테이블이 볼 수 없다.
그래서 이 애그리거트 간 규칙은 둘을 함께 읽는 응용 서비스가 테이블 실행기 안에서 확인한다(`AddChipsService`·`ApplyChipsReservationsService`·`CloseIdleTableService`).
반대로 테이블 안에서 닫히는 규칙(누가 다음 핸드에 참가하고 진입료를 내는가 — `seatForNextHand`, 0칩 좌석을 세우는가 — `standUpBusted`)은
`HoldemTable` 이 갖는다. 응용 계층은 결과를 받아 포트(`RecentDepartures`, 지갑)를 부르는 일만 한다.
