---
paths: ["**/wallet/**"]
---

# wallet

`wallet` 파일을 건드릴 때만 로드된다. 범용 규칙은 `CLAUDE.md` 에 있다.

## 이 컨텍스트에서만 지킬 것

- 잔액을 바꾸는 입구는 `Wallet.record()` 하나다. 반환값이 곧 저장할 원장 엔트리라, 원장을 빠뜨리는
  호출이 존재할 수 없다. 부호는 `Money` 가 아니라 `LedgerEntryType.direction` 이 나른다
  (`Money` 는 음수를 못 갖는다). 부호 있는 `Long` 이 도메인에 들어오는 자리는 `Adjustment` 하나뿐이다
- 환전은 요청 시점에 차감한다. 승인은 상태만 바꾼다 — 돈은 이미 나갔다.
  `ApproveWithdrawalRequestService` 에 `WalletRepository` 가 없는 것은 실수를 막기 위한 것이다
- 지갑은 명령 경로에서 lazy 로 만들어진다. 조회 경로에서는 만들지 않는다
  (`GET /api/wallet` 은 잔액 0). 그래서 "지갑 없음" 에러 코드가 없다
- 충전·환전 *요청* 테이블에는 FK 가 없다 — 테스트에서 합성 userId 로 요청을 만들어도 된다
