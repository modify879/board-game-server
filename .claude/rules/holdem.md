---
paths: ["**/holdem/**"]
---

# holdem

목표 규칙(하우스 룰)은 `docs/decisions/holdem-house-rules.md` — 이 파일은 구현된 동작을 적는다. 바꿀 때는 하우스 룰 문서와 맞춘다.

## 이 컨텍스트에서만 지킬 것

- **브로드캐스트 페이로드 타입에 홀카드를 담을 필드를 두지 않는다.** 예외는 둘뿐이다 — `ShownHandView` 는
  끝난 핸드의 `HandResult.shownSeatNos` 와 폴드 승자의 보여 주기(`ShowHandService`)로만, `RevealedHandView` 는 올인 런아웃 중(TDA) `Hand.revealedSeatNos` 로만 만든다. `/topic/tables/{id}` 에는 `TablePublicView` 만,
  `/user/queue/...` 에는 `SeatPrivateView` 만 흐르게 해 규칙 6을 "좌석마다 루프 돌 것을 기억하라" 가 아니라
  **컴파일러**로 보장한다. 한 타입에 nullable 홀카드 필드를 두고 마스킹으로 비우는 방식은 쓰지 않는다 —
  빠뜨리면 패가 새고, 빠뜨렸는지는 런타임에만 드러난다
- **방/좌석은 holdem 안에 둔다.** 별도 컨텍스트로 빼지 않는다 — 좌석이 버튼 이동·블라인드 포스팅·
  사이드팟 자격까지 얽혀 있어, 밖으로 빼면 홀덤에만 있는 개념이 공용 좌석 모델로 새어 나간다
- **`Chips` 는 holdem 자기 VO 다.** `wallet` 의 `Money` 를 쓰지 않고, 다른 게임과 공유하지도 않는다(규칙 7).
  `wallet` 은 바이인/캐시아웃에서 **application 계층에서만** 부른다(규칙 1)
- **셔플은 `Shuffler` 포트로 주입받는다**(규칙 5)
- **핸드 히스토리는 결과 요약만 남긴다(하우스 룰 11, 액션 로그 없음).** `HandSettler.settle` 이 같은 트랜잭션에서 `HandHistory` 포트로
  `holdem_hand_history`(핸드 1행)와 `holdem_hand_history_player`(참가 좌석마다 1행: 시작·종료 스택, 지급액, 홀카드, 공개 여부)를 쓴다.
  취소된 핸드(`CancelHandService`)는 남기지 않는다. 폴드 승자의 보여 주기는 `ShowHandService` 가 `markShown` 으로 그 테이블 최신 핸드의 `shown` 을 올린다.
  조회는 `GET /api/holdem/me/hands`(내가 참가한 핸드, 내 패는 항상·남의 패는 `shown` 일 때만, 나머지 null)와 관리자 `GET /api/admin/holdem/hands`(마스킹 없음) —
  마스킹은 `HandHistoryQueryService` 가 한다. 90일이 지난 핸드는 `HandHistoryPurger` 가 하루 한 번 지운다(플레이어 행은 FK cascade).
  진행 중 핸드는 이벤트 로그가 아니라 **상태 스냅샷 1행**만 둔다(이벤트 소싱은 재생 엔진이라는 정상 경로와 다른 코드를 하나 더 만드는데,
  거기서 틀리면 복구 시 돈이 틀어지고 그 오류는 서버가 죽었을 때만 드러난다). 직전 핸드의 공개 결과 1행(`holdem_last_hand_result`)은 재구독 스냅샷용이고 새 핸드가 시작되면 지운다
- **보여 주기(하우스 룰 10)의 폴드 승자 카드는 `LastHandResult` 에 두지 않는다.** 그건 공개 결과의 출처라 비공개 카드가 섞이면 재구독 스냅샷으로 샌다.
  `FoldWinnerCards` 포트(인메모리, 재시작하면 사라짐)에 정산 때 담고, 새 핸드·쇼다운 정산 때 지운다. `ShowHandService` 가 승자 본인·핸드 없음을 확인하고 한 번만 `shownHands` 에 붙인다(보드 3장 미만이면 `category` 는 null)
- **개인 큐 유실은 돈 문제다.** "당신 차례" 가 증발하면 타임아웃 자동 폴드로 칩이 날아간다 —
  `convertAndSendToUser` 경고 규칙(CLAUDE.md 규칙 6)을 여기서 특히 지킨다
- **진행 중 핸드 스냅샷에 덱을 담지 않는다.** 담으면 DB 를 읽을 수 있는 사람이 미래 카드를 안다.
  복원할 때 아직 딜되지 않은 카드로 덱을 새로 섞는다. 팟도 담지 않는다 — 좌석별 총 투입액에서
  유도되므로 두 곳이 어긋날 수 있다
- **베팅 라운드 스냅샷에는 `actedSinceLastFullRaise` 가 반드시 들어간다.** 빠지면 복구 후 BB 옵션이 사라지고
  이미 행동한 좌석이 다시 레이즈할 수 있게 된다. `HandStateJson` 의 `lastFullLevel` 은 옛 스냅샷 호환용으로만 남아 있다(읽을 때 무시)
- **턴 타이머(30초)와 연결 타이머(3분)는 상수를 공유하지 않는다.** 재는 대상(차례 무응답 vs
  자리만 차지하고 사라짐)과 만료 동작(폴드 vs 퇴장)이 다르다. 연결 끊김은 presence 표시만 바꾸고, 핸드 중에는
  턴 타임아웃이 처리한다. 핸드가 없는 동안 3분 끊겨 있으면 좌석을 떠난다 — 핸드가 진행 중이면 퇴장시키지 않고 감시를 다시 건다
- **관전은 공개 토픽 구독이다. 서버에 관전자 상태를 두지 않는다.** `/topic/tables/{id}` 는 인증된
  사용자면 누구나 구독할 수 있고, `/user/queue/tables/{id}` 는 **그 테이블 착석자만** 구독할 수
  있다(착석자가 아니면 SUBSCRIBE 를 조용히 버린다 — WARN 로그, ERROR 없음, 연결 유지). 공개 토픽을 여는 것이 안전한 이유는 `TablePublicView` 에 홀카드를 담을 필드가 (`ShownHandView`·`RevealedHandView`
  둘을 빼면) 없기 때문이다 — 그래서 **그 예외 밖에서 카드 성격의 필드를 추가하는 순간 관전이 곧
  정보 유출이 된다**
- **올인으로 더 베팅할 수 없으면 남은 보드를 한 번에 깔지 않는다.** `RunoutTimer` 가 `runout-step-delay` 간격으로 한 스트리트씩
  진행한다 — 공개 → 플랍/턴/리버 → 쇼다운 순. 결과를 한 메시지로 몰아 보내면 클라이언트 오류 하나로 이용자가 과정을 못 본다(사용자 결정)
- **BB 대기 규칙은 판을 굶겨 죽이지 않는다.** 후보(스택 양수 점유 좌석)가 2명 이상인데 대기를
  빼면 2명 미만이면, 전원의 대기를 풀고 참가시킨다. 이게 없으면 영구 데드락이다 — 참가자 부족으로
  예외를 던지는 순간 `@Transactional` 이 롤백해 블라인드 전진과 플래그 해제가 취소되고, 다음
  호출도 같은 상태라 영원히 실패한다. 테이블이 비었다 다시 차거나 정규 참가자가 파산하면 실제로 닿는다.
  완화가 걸리면 그 핸드의 즉시 포스팅 진입료도 면제한다 — 테이블이 사실상 다시 시작하는 셈이다
- **정산은 스택을 먼저 반영하고 그다음 0칩 좌석을 내보낸다.** 순서를 뒤집으면 `applyStacks` 가
  이미 빠진 좌석을 만나 터진다. 0칩 자동 기립은 핸드가 끝난 뒤라 going south 제약과 부딪히지
  않고, 돌려줄 칩이 없으므로 지갑을 부르지 않는다
- **좌석의 `awaitingBigBlind` 와 `owesImmediatePost` 는 다른 축이다.** 즉시 포스팅을 고르면 대기는
  바로 풀리지만 진입료는 아직 안 낸 상태라, 한 필드로는 "이미 정상 참가 중" 과 구분되지 않는다
- **포지션은 두 단계다. 테이블에 저장하는 것은 명목 포지션뿐이다.** `advanceBlinds` 는 대기 좌석까지 포함한
  후보로 회전(명목)을 정하고, `HandPositions.forParticipants` 가 실제 참가자로 좁힌다. 헤즈업 판정은 **실제
  참가자 수**로 한다 — 후보 수로 하면 대기 좌석이 빠진 판에서 버튼이 BB 와 같은 좌석이 되고 액션 순서가 뒤집힌다.
  헤즈업은 BB 가 아닌 한 좌석이 버튼·SB 를 겸하고(Robert's Rules: "the small blind is on the button"),
  BB 는 직전 BB 다음 좌석으로 간다(TDA Rule 34: 같은 좌석이 연속으로 BB 를 내지 않는다). 헤즈업 뒤 3인이 되면
  버튼은 "직전 SB" 가 아니라 새 SB 바로 앞 좌석이다 — 헤즈업에서는 버튼이 곧 SB 였다
- **새 참가자는 SB·버튼 자리에서는 포스팅을 자청해도 딜인되지 않는다.** Robert's Rules: "You must wait until
  the button passes." 어기면 SB 와 진입료를 이중으로 내 `committed > currentBet` 이 되고, 콜이
  `CHIPS_NEGATIVE` 로 터진다. 차단된 좌석은 `owesImmediatePost` 를 그대로 들고 기다린다
- **쇼다운까지 간 좌석은 전원 공개한다(사용자 결정, 머크 없음).** `showdownOrder`(TDA 17: 마지막
  라운드의 마지막 공격자부터, 없으면 버튼 왼쪽부터)는 공개 패를 나열하는 순서로만 쓴다. 모두
  폴드한 판은 공개하지 않는다
- **다음 핸드 카운트다운은 상대 시간(단조 시계)만 쓴다 — `NextHandCountdown`, 메모리 전용이다.**
  `next_hand_at` 같은 DB 컬럼을 두지 않는다. 발화 이후 벽시계로 다시 비교하지 않는다 — WSL2 호스트의
  벽시계가 뒤로 튀는데(문서 배경) 그 비교가 조용히 실패하면 테이블이 영영 멈춘다. 입장(착석/참가
  요청 처리)마다 카운트다운을 전체 지연으로 다시 시작한다(리셋) — 사용자 결정: 3초 남았을 때 새
  참가자가 들어와도 이어지지 않고 처음부터 다시 센다. 기립으로 후보가 2명 미만이면 취소하고, 아니면
  건드리지 않는다(나가는 쪽은 리셋하지 않는다). 정산 뒤 후보가 2명 이상이면 새로 건다(핸드 도중엔
  카운트다운이 없었으므로 항상 전체 지연이다). 재시작하면 인메모리 예약이 사라지므로 `HandRecovery` 가
  부팅 시 조건에 맞는 테이블마다 새로 건다. 수동 시작은 없다 — 대기 중 끼어들면 다른 좌석의 기립 결정
  시간을 빼앗는다
- **30초 무응답은 체크할 수 있으면 체크, 아니면 폴드하고 폴드한 좌석은 판 도중 즉시 기립시킨다** — 폴드한 좌석의
  스택은 더 변하지 않으므로 그 자리에서 정산하고, 그 핸드가 끝날 때까지 그 좌석 번호에는 앉을 수 없다(정산이 새 착석자를 덮어쓴다)
- **바이인은 사용자가 20~100BB(4,000~20,000) 안에서 고르고, 자동 합산은 없다.** 지갑에 들어온 돈(충전 승인·출금 취소/반려·관리자 증액)은 지갑에만 남고
  `WalletCredited` 를 듣는 쪽은 holdem 에 없다. 스택에 넣는 길은 두 가지뿐이다 — 착석 때의 바이인과 핸드 사이의 **칩 추가**(`POST /api/holdem/seat/chips`,
  `AddChipsService`: 핸드 진행 중이면 `HAND_IN_PROGRESS`, 결과 스택이 100BB 를 넘으면 `CHIP_ADD_OUT_OF_RANGE`, 테이블 → 지갑 락 순서로 이체한 뒤 공개 뷰를 브로드캐스트).
  범위는 `HoldemTable.buyInRange(obligation)` 하나가 정한다 — 도메인 `sitDown` 은 하한(20BB)만 보고 상한은 `SeatRequester` 가 본다. 잔액이 바이인보다 적으면
  `INSUFFICIENT_BALANCE`(wallet 과 같은 코드 문자열 — 클라이언트가 하나만 처리한다). 대기열 항목(`JoinQueueEntry`)이 고른 금액을 들고 있고
  `AdmitJoinRequestService` 가 정확히 그만큼만 `WalletTransfer.toGame` 으로 옮긴다. 범위·잔액은 `GET /api/holdem/tables/{id}/buy-in`(`BuyInQueryService`; 테이블 생성 전에는 기본 블라인드 기준 `GET /api/holdem/buy-in`)이 알려 준다
- **재착석 의무(ratholing 방지)**: 떠난 사람이 가져간 금액을 `RecentDepartures` 가 30분 동안 기억한다(인메모리 — 재시작하면 사라져 한 번은 샐 수 있다). 기립(`StandUpService`)·
  만료 폴드 기립(`ExpireTurnService`)은 돌려받은 금액을, 0칩 자동 기립(`HandSettler`)은 0(이전 의무를 지운다)을 덮어쓴다. 의무가 있으면 `buyInRange` 의 하한이 그 금액으로
  올라가고, 100BB 를 넘으면 상한도 따라 올라간다(의무가 상한보다 우선). 잔액이 의무에 못 미치면 `INSUFFICIENT_BALANCE`
- **착석은 항상 대기열을 거친다 — 핸드 진행 여부와 무관하다.** 좌석은 고르지 않는다: 가장 낮은 번호의 빈 좌석에
  FIFO 순서대로 앉힌다. 대기열은 인메모리(`JoinQueue`)다 — 좌석 배정 전이라 돈이 걸려 있지 않고, 재시작하면
  비워져도 안전하다(복구할 게 없다). 트리거는 셋: 대기열 삽입 커밋(SitDownService), 핸드 정산(HandSettler),
  기립 커밋(StandUpService, 좌석 하나가 빈다). 항목마다 커밋 후 별도 트랜잭션(AdmitJoinRequestService,
  REQUIRES_NEW)으로 처리한다 — 같은 트랜잭션이면 지갑 이체 실패 하나가 다른 처리 전체를 롤백시킨다. 연결이
  끊기면 대기열에서도 빠진다(ConnectionTimer). 처리 못 하는 항목은 버린다. 취소는 그 테이블의 `TableExecutor` 를 거쳐 착석 처리와 순서대로 실행된다.
- **테이블의 상태를 바꾸는 명령은 전부 `TableExecutor` 로 그 테이블의 스레드에서 실행한다 — 한 테이블에 쓰는 쪽은 하나다(single writer).** 새 명령 진입점도 반드시 거친다. 트랜잭션은 그 스레드 안에서 연다. 같은 테이블 스레드 안에서 다시 `call` 하면 인라인으로 돈다. 서버 한 대 전제.
- **공개/개인 뷰에는 테이블별 순번(`seq`, `TableViewSequence`)이 붙는다.** `HandBroadcaster` 는 커밋 후가 아니라
  `BEFORE_COMMIT` 에서 순번을 따는데, 같은 행을 바꾸는 트랜잭션들은 `saveAndFlush` 의 행 락으로 커밋 순서대로만
  여기 도달하기 때문이다(순번 순서 = 커밋 순서). 구독 스냅샷도 테이블 실행기에서 읽는다 — 같은 seq 는 같은 상태다. 실제 전송은 여전히 `afterCommit` 이라 규칙 6은 그대로 지켜진다.
  `TableViewSequence` 는 메모리 전용이라 재시작하면 0부터 다시 세지만, 재시작은 모든 소켓을 끊으므로 클라이언트가
  재구독(재접속 포함) 시 채널별 last seq 를 리셋하면 문제없다. `WebSocketConfig` 의 `setPreservePublishOrder(true)`
  와 함께 써야 한다 — 순번만 있고 세션별 발행 순서가 안 지켜지면 같은 세션 안에서도 메시지가 뒤섞인다
- **테이블별 인메모리 상태는 `TableId` 를 키로 두고 `TableClosed` 커밋 후 리스너로 스스로 치운다.** `TableClosedListenersTest` 가 `TableId` 키 필드를 가진 컴포넌트를 스캔해 리스너를 강제한다 — Long 키로 두면 이 검사에 보이지 않는다
- **테이블은 비면 자동으로 닫힌다.** 착석 0명·핸드 없음·대기열 없음이 1분(`IdleTableSweeper`, 15초 스윕) 이어지면 `closed_at` 으로 닫는다 —
  행을 지우지 않는다(지갑 원장이 테이블 id 를 참조한다). 닫힌 테이블은 `HoldemTableRepository.findById` 가 없는 것으로 돌려 명령·조회 모두 `TABLE_NOT_FOUND`.
  닫힐 때 공개 토픽에 `{"tableId","type":"TABLE_CLOSED"}` 를 보내고, 닫힌·없는 테이블을 재구독하면 스냅샷 대신 같은 알림을 다시 보낸다
  (ERROR 프레임으로 거부하지 않는다 — STOMP 세션 전체가 끊긴다)
- join-queue 구독도 스냅샷(대기 순번 또는 착석)을 보낸다 — SUBSCRIBE 와 REST 요청의 도착 순서가 보장되지 않고 심플 브로커는 SUBSCRIBE 에 RECEIPT 를 주지 않으므로. DROPPED 는 재전송되지 않는다
- **`SerialTableExecutor` 는 실행기를 새로 만들 때만 `existsOpen` 으로 확인한다.** 없거나 닫힌 테이블엔 실행기를 만들지 않고 호출자 스레드에서 돌린다 —
  임의 id 요청·닫힘 직후 타이머가 실행기를 되살려 쌓지 않게
- **타이머 스케줄러는 가상 스레드 `SimpleAsyncTaskScheduler` 다.** 단 `scheduleWithFixedDelay` 작업은 모든 타이머를 깨우는 단일 스케줄러 스레드에서 돈다 —
  거기서 `tableExecutor.call` 로 기다리면 서버 전체 타이머가 멈추므로 `post` 한다(`IdleTableSweeper`)
