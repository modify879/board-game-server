## 홀덤 구현

`.claude/rules/holdem.md` 의 규칙이 왜 그렇게 됐는지. 게임 규칙에 대한 사용자 결정은 `holdem-house-rules.md`.

### 홀카드 필드를 타입에서 없애는 이유
`/topic/tables/{id}` 에는 `TablePublicView` 와 카드 필드가 없는 `TableClosedView`, `/user/queue/tables/{id}` 에는 `SeatPrivateView`,
`/user/queue/holdem/join-queue` 에는 카드 필드가 없는 `JoinQueueView` 만 흐르게 해 CLAUDE.md 규칙 6 을 "좌석마다 루프 돌 것을 기억하라" 가 아니라 **컴파일러**로 보장한다.
한 타입에 nullable 홀카드 필드를 두고 마스킹으로 비우면 빠뜨렸는지가 런타임에만 드러난다.
예외 둘: `ShownHandView` 는 끝난 핸드의 `HandResult.shownSeatNos` 와 폴드 승자의 보여 주기(`ShowHandService`)로만, `RevealedHandView` 는 올인 런아웃 중(TDA) `Hand.revealedSeatNos` 로만 만든다.
개인 뷰의 `SeatPrivateView.hand`(`PrivateHandView`)는 `null` = 핸드 없음 / 이번 핸드 비참가이고, 개인 큐에는 주인 본인의 데이터만 흐르므로 위 "nullable 마스킹" 과 다르다.

### 관전은 공개 토픽 구독
`/topic/tables/{id}` 는 인증된 사용자면 누구나, `/user/queue/tables/{id}` 는 그 테이블 착석자만 구독한다(`websocket.md`). 공개 토픽이 안전한 이유는 `TablePublicView` 에 예외 둘을 빼면 홀카드 필드가 없어서다.

### 테이블 변경은 단일 실행기(single writer)
같은 테이블을 바꾸는 명령이 한 스레드(`TableExecutor`)에서만 돌면 락 없이 순서가 보장된다. 새 명령 진입점도 반드시 거친다.
예외: `CreateTableService`(새 테이블 id 라 동시에 쓰는 쪽이 없다), qa 전용 경로(`ResetQaAccountsService`, `ReserveNextDeckService`).
트랜잭션은 그 스레드 안에서 연다. 같은 테이블 스레드 안에서 다시 `call` 하면 인라인으로 돈다(트랜잭션이 활성이면 예외 — `transactions.md`). 서버 한 대 전제.
`SerialTableExecutor` 는 실행기를 새로 만들 때만 `existsOpen` 으로 확인한다 — 없거나 닫힌 테이블엔 실행기를 만들지 않고 호출자 스레드에서 돌린다.
`scheduleWithFixedDelay` 작업은 모든 타이머를 깨우는 단일 스케줄러 스레드에서 돌므로(타이머 스케줄러 자체는 가상 스레드 `SimpleAsyncTaskScheduler`) 거기서 `call` 로 기다리면 서버 전체 타이머가 멈춘다(`IdleTableSweeper` 는 `post`).

### seq 를 BEFORE_COMMIT 에서 따는 이유
`HandBroadcaster` 는 커밋 후가 아니라 `BEFORE_COMMIT` 에서 순번을 딴다. 같은 테이블을 바꾸는 명령은 `TableExecutor` 한 스레드에서 직렬로 돌아 커밋 순서대로만 여기 도달하므로 순번 순서 = 커밋 순서다. 행 락이 아니다 — 바뀐 게 없는 merge 는 UPDATE 를 내지 않는다.
구독 스냅샷도 테이블 실행기에서 읽는다 — 같은 seq 는 같은 상태다(`HoldemSubscriptionSnapshotListener` 가 `HoldemTableRepository`·`HandStore` 를 직접 읽는 것은 규칙 3 의 의도된 예외 — `HandBroadcaster` 와 같은 애그리거트에서 스냅샷을 조립해야 한다). 실제 전송은 여전히 `afterCommit` 이라 규칙 6 은 그대로 지켜진다.
`WebSocketConfig` 의 `setPreservePublishOrder(true)` 와 함께 써야 한다 — 순번만 있고 세션별 발행 순서가 안 지켜지면 같은 세션 안에서도 메시지가 뒤섞인다.
`TableViewSequence` 는 메모리 전용이라 재시작하면 0부터 다시 세지만, 클라이언트가 재구독 시 채널별 last seq 를 리셋하면 문제없다.

### BB 대기 완화와 데드락
후보(스택 양수 점유 좌석)가 2명 이상인데 BB 대기를 빼면 2명 미만이면, 전원의 대기를 풀고 참가시킨다. 없으면 영구 데드락이다 — 참가자 부족 예외가 `@Transactional` 을 롤백해 블라인드 전진과 플래그 해제가 취소되고, 다음 호출도 같은 상태라 영원히 실패한다.
완화가 걸리면 그 핸드의 즉시 포스팅 진입료도 면제한다. 참가자·진입료·플래그 갱신은 `HoldemTable.seatForNextHand` 한 곳이 정하고 `HandStarter` 는 조율만 한다.
`awaitingBigBlind` 와 `owesImmediatePost` 가 다른 축인 이유: 즉시 포스팅을 고르면 대기는 바로 풀리지만 진입료는 아직 안 낸 상태라, 한 필드로는 "이미 정상 참가 중" 과 구분되지 않는다.

### 명목 포지션과 실제 포지션
`advanceBlinds` 는 대기 좌석까지 포함한 후보로 회전(명목)을 정하고, `HandPositions.forParticipants` 가 실제 참가자로 좁힌다. 테이블에 저장하는 것은 명목 포지션뿐이다.
헤즈업 판정은 실제 참가자 수로 한다 — 후보 수로 하면 대기 좌석이 빠진 판에서 버튼이 BB 와 같은 좌석이 되고 액션 순서가 뒤집힌다.
헤즈업은 BB 가 아닌 한 좌석이 버튼·SB 를 겸하고, BB 는 직전 BB 다음 좌석으로 간다(TDA 34). 헤즈업 뒤 3인이 되면 버튼은 "직전 SB" 가 아니라 새 SB 바로 앞 좌석이다.
실제 포지션은 진행 중 핸드 스냅샷(`Hand.buttonSeatNo`·`smallBlindSeatNo`·`bigBlindSeatNo`)에만 있다. 공개 뷰의 `buttonSeatNo` 는 핸드가 있으면 그 핸드의 실제 버튼, 없으면 테이블의 명목 버튼이다.

### 버튼~첫 블라인드 구간 딜인 금지
구간 = 명목 버튼(포함)부터 원형(`MAX_SEATS`)으로 버튼 뒤 첫 블라인드까지. 첫 블라인드는 SB 이고 dead SB 면 BB 다. SB 는 구간에 포함하고 BB 는 제외한다(SB~BB 사이 좌석은 막지 않는다).
dead button 으로 BB 가 빈 좌석을 건너뛰면 버튼~SB 사이에 신규 좌석이 앉을 수 있어 버튼·SB 좌석 한 점만 막으면 놓친다.
Robert's Rules: "You must wait until the button passes." 어기면 SB 와 진입료를 이중으로 내 `committed > currentBet` 이 되고 콜이 `CHIPS_NEGATIVE` 로 터진다.
차단된 좌석은 `owesImmediatePost` 를 그대로 들고 기다린다. BB 대기 완화(참가자가 2명 미만이 되면 후보 전원 참가)는 차단된 좌석도 딜인시킨다 — 이때는 진입료를 면제한다.

### 진행 중 핸드는 스냅샷 1행, 이벤트 로그가 아니다
이벤트 소싱은 정상 경로와 다른 재생 코드를 하나 더 만든다. 그래서 진행 중 핸드는 상태 스냅샷 1행만 둔다.
스냅샷에 덱을 담지 않는 이유: 담으면 DB 를 읽을 수 있는 사람이 미래 카드를 안다(복원 때 아직 딜되지 않은 카드로 덱을 새로 섞는다). 팟도 좌석별 총 투입액에서 유도되므로 두 곳이 어긋날 수 있어 담지 않는다.
직전 핸드의 공개 결과 1행(`holdem_last_hand_result`)은 재구독 스냅샷용이고 새 핸드가 시작되면 지운다. 폴드 승자의 카드는 그 출처에 섞이면 안 되므로 `FoldWinnerCards` 포트에 따로 둔다.

### 핸드 히스토리는 결과 요약만
하우스 룰 11, 액션 로그 없음. `HandSettler.settle` 이 같은 트랜잭션에서 `HandHistory` 포트로 쓴다. 취소된 핸드(`CancelHandService`)는 남기지 않는다.
남의 패는 `shown` 일 때만 보이게 하는 마스킹은 `HandHistoryQueryService` 가 한다.

### 재착석 의무 저장
`RecentDepartures` 는 `holdem_rejoin_obligation` 에 (사용자, 테이블)당 1행으로 저장한다(PK `user_id, table_id`) — 의무는 떠난 테이블에만 적용된다. 기립과 같은 트랜잭션에 커밋되고, 만료된 행은 지우지 않고 읽을 때 무시한다.
예전 `holdem_recent_departure`(PK `user_id`)는 `ddl-auto: update` 가 PK 를 바꾸지 못해 새 테이블 이름으로 옮겼고, 옛 테이블은 더 쓰지 않는다(남은 행은 무시한다).
기립(`StandUpService`)·만료 폴드 기립(`ExpireTurnService`)은 돌려받은 금액을, 0칩 자동 기립(`HandSettler`)은 0(그 테이블의 이전 의무를 지운다)을 덮어쓴다. QA 리셋은 `clear(userId)` 로 모든 테이블의 의무를 지운다.
의무는 `buyInRange` 하한을 올리고 최대 바이인 상한보다 우선한다.

### 참가 대기열
착석은 항상 대기열(`JoinQueue`)을 거친다 — 핸드 진행 여부와 무관하다. 좌석은 고르지 않는다: 가장 낮은 번호의 빈 좌석에 FIFO 순서대로 앉힌다.
대기열은 인메모리다 — 좌석 배정 전이라 돈이 걸려 있지 않고, 재시작하면 비워져도 안전하다. 연결이 끊기면 대기열에서도 빠진다(`ConnectionTimer`).
트리거(`JoinRequestsDue`)마다 항목을 커밋 후 별도 트랜잭션(`AdmitJoinRequestService`, `REQUIRES_NEW`)으로 처리한다 — 같은 트랜잭션이면 지갑 이체 실패 하나가 다른 처리 전체를 롤백시킨다.
핸드 진행 중이거나 빈 좌석이 없어 막힌(`Blocked`) 항목은 큐에 남고, 처리가 실패한 항목만 버린다. 취소는 그 테이블의 `TableExecutor` 를 거쳐 착석 처리와 순서대로 실행된다.
바이인 자동 합산은 없다(하우스 룰 6) — 지갑에 들어온 돈은 지갑에만 남고 `WalletCredited` 를 듣는 쪽은 holdem 에 없다. 스택에 넣는 길은 착석 바이인과 칩 추가(`AddChipsService`) 둘뿐이다.
칩 추가는 핸드 중이면 `chipsReservation` 에 예약만 걸고 정산 뒤(`ChipsReservationsDue`) 좌석마다 `SeatChipsReservationApplier`(`REQUIRES_NEW`)가 적용한다 — 지갑 실패가 정산을 롤백하지 않게. 부팅 시 `HandRecovery` 가 남은 예약 적용을 다시 올린다. 잔액 부족은 wallet 과 같은 코드 `INSUFFICIENT_BALANCE`.

### join-queue 구독도 스냅샷을 보내는 이유
SUBSCRIBE 와 REST 요청의 도착 순서가 보장되지 않고, 심플 브로커는 SUBSCRIBE 에 RECEIPT 를 주지 않는다. 그래서 구독 시 스냅샷(대기 순번 또는 착석)을 보낸다. DROPPED 는 재전송되지 않는다.

### 테이블 자동 닫힘
마지막 사람이 떠나면 `JoinRequestsDue` 처리(대기열 착석) 직후 `JoinRequestsProcessor` 가 `closed_at` 으로 닫는다. 아무도 앉은 적 없는 테이블·재시작 뒤 남은 빈 테이블은 `IdleTableSweeper` 폴백이 닫는다.
행을 지우지 않는다 — 지갑 원장이 테이블 id 를 참조한다. 닫힌 테이블은 `HoldemTableRepository.findById` 가 없는 것으로 돌려 명령·조회 모두 `TABLE_NOT_FOUND`.
닫힐 때 공개 토픽에 `{"tableId","type":"TABLE_CLOSED"}` 를 보내고, 닫힌·없는 테이블을 재구독하면 스냅샷 대신 같은 알림을 다시 보낸다 — ERROR 프레임으로 거부하면 STOMP 세션 전체가 끊긴다.

### 방/좌석은 holdem 안에 둔다
별도 컨텍스트로 빼지 않는다.
