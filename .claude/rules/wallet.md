---
paths: ["**/wallet/**"]
---

# wallet

## 이 컨텍스트에서만 지킬 것

- 잔액을 바꾸는 입구는 `Wallet.record()` 하나다. 반환값이 곧 저장할 원장 엔트리라, 원장을 빠뜨리는
  호출이 존재할 수 없다. 부호는 `Money` 가 아니라 `LedgerEntryType.direction` 이 나른다
  (`Money` 는 음수를 못 갖는다). 부호 있는 `Long` 이 도메인에 들어오는 자리는 `Adjustment` 하나뿐이다
- 환전은 요청 시점에 차감한다. 승인은 상태만 바꾼다 — 돈은 이미 나갔다.
  `ApproveWithdrawalRequestService` 에 `WalletRepository` 가 없는 것은 실수를 막기 위한 것이다
- 지갑은 명령 경로에서 lazy 로 만들어진다. 조회 경로에서는 만들지 않는다
  (`GET /api/wallet` 은 잔액 0). 그래서 "지갑 없음" 에러 코드가 없다
- 충전·환전 최소액은 설정 `app.wallet.min-deposit-amount`/`min-withdrawal-amount`(기본 1000)이다. `WalletProperties` 가 읽어 서비스가 도메인 팩토리에 값으로 넘기고, 100 단위(`MONEY_UNIT`)는 도메인 불변식이라 설정 최소액이 단위의 배수가 아니면 기동 실패한다
- 충전·환전 *요청* 테이블에는 FK 가 없다 — 저장소로 직접 만들 때는 합성 userId 를 써도 된다. 서비스를 거치면 지갑을 열고 활성 확인을 하므로 실제 사용자가 필요하다
- **회원 탈퇴는 지갑 락으로 직렬화한다.** `LockWalletForAccountClosureUseCase`(MANDATORY)가 user 트랜잭션 안에서 지갑을 잠그고 비었는지 본다.
  돈이 들어오거나 요청이 생기는 경로(바이인·충전 요청·환전 요청·관리자 조정)는 지갑 락을 잡은 **뒤에** `UserExistence` 로 활성을 확인한다 — 락 전에 확인하면 기다리는 동안 커밋된 탈퇴를 못 본다.
  충전 요청도 그래서 지갑을 연다. 실패 코드는 `WALLET_OWNER_NOT_FOUND`. 환급(`TransferFromGame`)은 확인하지 않는다 — 탈퇴자는 앉아 있을 수 없고, 막으면 칩이 사라진다
- 지갑·요청을 바꾸는 조회는 `PESSIMISTIC_WRITE` 로 잠그고 읽기 전용 조회(JDSL)와 메서드를 아예
  나눈다. 잠그는 메서드는 `@Transactional(propagation = MANDATORY)` 라 반드시 트랜잭션 안에서 부른다 —
  밖에서 부르면 `IllegalTransactionStateException` 으로 실패한다(짧은 트랜잭션을 조용히 열지 않는다).
  Postgres 는 읽기 전용 트랜잭션에서 `FOR UPDATE` 를 거부하므로 읽기 전용 트랜잭션에 참여해서도 안 된다
- 조정·반려 사유에 길이 상한을 두지 않는다 — 관리자 전용(사용자 결정)
