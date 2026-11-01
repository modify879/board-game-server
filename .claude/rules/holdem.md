---
paths: ["**/holdem/**"]
---

# holdem

목표 규칙(하우스 룰)은 `docs/decisions/holdem-house-rules.md`, 이유·메커니즘은 `docs/decisions/holdem.md` — 이 파일은 어기면 깨지는 것만 적는다. 바꿀 때는 하우스 룰 문서와 맞춘다.

## 정보 노출

- 브로드캐스트 페이로드 타입에 홀카드 필드를 두지 않는다. 예외는 `ShownHandView`(`HandResult.shownSeatNos`·`ShowHandService`)와 `RevealedHandView`(올인 런아웃 중 `Hand.revealedSeatNos`) 둘뿐. nullable 홀카드를 마스킹으로 비우는 방식 금지 — 빠뜨리면 패가 샌다. 개인 뷰 `SeatPrivateView.hand = null` 은 위반이 아니다(개인 큐엔 본인 데이터만 흐른다)
- 관전은 공개 토픽 구독이다. 서버에 관전자 상태를 두지 않는다. 공개 뷰에 위 예외 밖의 카드성 필드를 추가하면 관전이 곧 유출이다
- 폴드 승자의 보여 주기 카드는 `LastHandResult` 에 두지 않는다 — 재구독 스냅샷으로 샌다. `FoldWinnerCards`(인메모리)에 담고 새 핸드·쇼다운 정산 때 지운다
- 진행 중 핸드 스냅샷에 덱·팟을 담지 않는다 — 덱은 미래 카드가 DB 로 새고, 팟은 좌석별 투입액과 어긋난다. 복원 때 덱은 새로 섞는다
- 베팅 라운드 스냅샷에는 `actedSinceLastFullRaise` 가 반드시 들어간다 — 빠지면 복구 후 BB 옵션이 사라지고 행동한 좌석이 다시 레이즈한다
- 개인 큐 유실은 돈 문제다(당신 차례 증발 → 자동 폴드로 칩 손실). `convertAndSendToUser` 경고 규칙(`websocket.md`)을 특히 지킨다
- 닫힌·없는 테이블 재구독을 ERROR 프레임으로 거부하지 않는다 — STOMP 세션 전체가 끊긴다. `TABLE_CLOSED` 알림을 다시 보낸다
- join-queue 구독도 스냅샷을 보낸다(SUBSCRIBE-REST 순서 미보장). DROPPED 는 재전송하지 않는다
- 공개/개인 뷰의 `seq`(`TableViewSequence`)는 `HandBroadcaster` 가 `BEFORE_COMMIT` 에서 따고, `WebSocketConfig.setPreservePublishOrder(true)` 와 함께 쓴다 — 하나라도 빠지면 메시지가 뒤섞인다. 구독 스냅샷도 테이블 실행기에서 읽는다

## 실행기·동시성

- 테이블 상태를 바꾸는 명령은 전부 `TableExecutor` 를 거친다(single writer) — 새 진입점도. 예외는 `CreateTableService` 와 qa 전용 경로(`ResetQaAccountsService`·`ReserveNextDeckService`)뿐. 트랜잭션은 실행기 스레드 안에서 연다. 서버 한 대 전제
- 테이블별 인메모리 상태는 `TableId` 키로 두고 `TableClosed` 커밋 후 리스너로 치운다 — Long 키면 `TableClosedListenersTest` 가 못 본다
- `SerialTableExecutor` 는 실행기를 새로 만들 때만 `existsOpen` 을 본다 — 임의 id·닫힘 직후 타이머가 실행기를 되살려 쌓는다
- `scheduleWithFixedDelay` 안에서 `tableExecutor.call` 로 기다리지 않는다(`post`) — 모든 타이머를 깨우는 단일 스레드가 멈춘다
- 착석(`AdmitJoinRequestService`)·칩 적용(`SeatChipsReservationApplier`)은 항목마다 `REQUIRES_NEW` — 지갑 실패 하나가 다른 처리·정산을 롤백한다
- 칩 적용이 던지면 예약을 버리고 0칩이면 기립시킨다 — 0칩 좌석이 영영 앉아 있다
- "핸드가 진행 중인가" 는 `Hand?.isInProgress` / `HandStore.findInProgress` 하나로 묻는다. 핸드 객체나 "저장된 핸드 유무"가 필요한 곳(`StartScheduledHandService`·`HandRecovery`·정산·액션)만 `find`. `find != null` 과 `!isFinished` 를 따로 쓰지 않는다
- 턴 타이머와 연결 타이머는 상수를 공유하지 않는다. 연결 타이머 만료 때 핸드가 진행 중이면 퇴장시키지 않고 감시를 다시 건다
- 카운트다운(`NextHandCountdown`)은 상대 시간(단조 시계)·메모리 전용이다. `next_hand_at` 같은 DB 컬럼을 두지 않고 발화 뒤 벽시계로 다시 비교하지 않는다(`concurrency.md`)

## 정산·순서

- 정산은 스택 반영 → 0칩 기립 순서다 — 뒤집으면 `applyStacks` 가 빠진 좌석을 만나 터진다. 0칩 기립은 `HoldemTable.standUpBusted`, 재착석 의무 기록은 `HandSettler`, 지갑은 부르지 않는다. 칩 추가 예약이 있는 0칩 좌석(퇴장 예약 없음)은 건너뛴다(`ChipsReservationsDue` 가 처리, `JoinRequestsDue` 앞)
- 테이블 정책은 항상 `table.policy` 를 거친다. 블라인드·바이인 상수 금지 — 설정을 바꾸면 열린 테이블 규칙이 판 도중 바뀐다
- 바이인 범위는 `HoldemTable.buyInRange(obligation)` 하나가 정한다. 도메인 `sitDown` 은 하한만, 상한은 `SeatRequester` 가 본다. `JoinQueueEntry` 가 든 금액만큼만 `WalletTransfer.toGame` 으로 옮긴다
- 퇴장 예약(`leave_after_hand`·`leave_reservation`)은 DB 에 저장한다. 실행은 `LeaveReservations.standUpAll`(`HandSettler.settle` 0칩 기립 뒤·`JoinRequestsDue` 앞, `CancelHandService`). 좌석이 사라지면 예약도 사라진다. 종류가 null 인 레거시 행은 SPECTATE
- 만료 폴드로 기립한 좌석 번호는 그 핸드가 끝날 때까지 앉을 수 없다 — 정산이 새 착석자를 덮어쓴다
- 핸드 이력은 `HandSettler.settle` 과 같은 트랜잭션에서 쓴다. 취소된 핸드는 남기지 않는다

## 포지션·블라인드

- 포지션은 두 단계다. 테이블에는 명목 포지션만 저장하고 실제는 `Hand` 스냅샷에만 있다. 헤즈업 판정은 실제 참가자 수로 한다(`HandPositions.forParticipants`) — 후보 수로 하면 버튼이 BB 와 겹치고 액션 순서가 뒤집힌다
- BB 대기 완화: 후보가 2명 이상인데 대기를 빼면 2명 미만이면 전원 참가시키고 그 핸드의 진입료는 면제한다 — 없으면 영구 데드락. 참가자·진입료·플래그는 `HoldemTable.seatForNextHand` 한 곳이 정한다
- `awaitingBigBlind` 와 `owesImmediatePost` 는 다른 축이다 — 한 필드로는 "이미 정상 참가 중" 과 구분되지 않는다
- 버튼~첫 블라인드 구간(명목 버튼 포함, SB 포함·BB 제외, dead SB 면 BB 가 첫 블라인드)에서 신규 좌석을 딜인하지 않는다 — 이중 납부로 `committed > currentBet` 이 되어 콜이 `CHIPS_NEGATIVE` 로 터진다. 차단된 좌석은 `owesImmediatePost` 를 들고 기다린다(BB 대기 완화는 이들도 딜인, 진입료 면제)
- 헤즈업은 BB 아닌 쪽이 버튼·SB 를 겸한다(TDA 34). 헤즈업 뒤 3인이 되면 버튼은 새 SB 바로 앞 좌석이다

## 의도된 한계

- `NextHandTimer.restart` 는 호출자 트랜잭션 커밋 전에 예약한다(롤백이면 예약이 남지만 `onFire` 가 재확인한다). `cancel` 은 트랜잭션 안이면 커밋 뒤로 미룬다
- `InMemoryFoldWinnerCards` 는 재시작하면 사라진다 — 그 핸드의 폴드 승자가 카드를 못 보여 줄 뿐이다. DB 에 저장하지 않는다
- `InMemoryJoinQueue` 는 락 하나로 모든 테이블을 직렬화한다(락 안은 작은 메모리 작업뿐, 지갑 I/O·DB 는 락 밖). 이 락이 병목이 되면 그때 테이블별로 쪼갠다
- `TableViewSequence` 는 메모리 전용이라 재시작하면 0부터 센다 — 클라이언트가 재구독 시 채널별 last seq 를 리셋해야 한다

## QA 덱

- `POST /api/admin/qa/holdem/tables/{tableId}/next-deck`(ADMIN, qa 프로필만)은 다음 핸드 한 번만 덱을 지정한다(`QaNewHandShuffler`). 딜 순서는 버튼 다음 참가 좌석부터 두 바퀴, 번 카드 없음. 고정 덱 핸드는 복원 시에도 그 순서를 유지한다(`QaRestoreShuffler`)
