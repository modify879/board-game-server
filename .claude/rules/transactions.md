---
paths:
  - "**/application/**"
  - "**/infrastructure/**"
  - "**/presentation/ws/**"
---

# 트랜잭션·락

- **돈을 잠그는 순서는 항상 요청·테이블 → 지갑이다.** 충전·환전 요청 → 지갑 순으로 DB 락을 잡는다. 홀덤은 테이블 행을 잠그지 않고 테이블 실행기(`TableExecutor`)
  안에서 지갑 이체를 부른다("테이블 먼저" 는 실행기 직렬화다). 지갑을 먼저 잠근 뒤 요청 행이나 홀덤 테이블 실행기를 타지 않는다 — 순서를 뒤집으면
  데드락이다. 예외는 요청 *생성*(`RequestDepositService`·`RequestWithdrawalService`)뿐이다 — 지갑을 잠근 뒤 새 행을 INSERT 하는데, 아직 없는 행이라 남이 쥔 락이 없다.
- **사용자 행 락(`UserRepository.findByIdForUpdate`)은 지갑 락 뒤에만 잡는다** — 순서는 지갑 행 → 사용자 행. 사용자 행을 잠근 트랜잭션에서 지갑·요청 행이나 홀덤 실행기를 타지 않는다.
  락은 `for no key update` 라 FK INSERT(지갑·좌석)는 막지 않지만, 닉네임 같은 유니크 컬럼을 바꾸는 UPDATE 는 강한 락이 된다
- **사용자를 FK 로 참조하는 행(지갑·좌석)은 그 사용자의 지갑 락을 잡은 뒤에만 INSERT 한다.** 탈퇴는 지갑 락을 쥔 채 `users` 행을 UPDATE 한다(유니크 인덱스 컬럼이라 강한 행 락). 반대 순서면 데드락이고, 바깥 트랜잭션이 멈춘 채 `REQUIRES_NEW` 가 기다리는 구조에서는 Postgres 가 감지하지 못해 `lock_timeout` 에서야 끝난다
- **같은 트랜잭션에 참여한 `@Transactional` 프록시를 예외가 한 번이라도 넘으면 바깥에서 잡아도 트랜잭션은 rollback-only 다.**
  커밋 시점에 `UnexpectedRollbackException` 으로 터진다. 실패를 격리해야 하면 호출 단위로 `REQUIRES_NEW` 를 쓴다(`AdmitJoinRequestService`)
- **user 의 서비스(`LoginService`·`RefreshTokenService`·`ChangeUserRoleService`·`UnlockUserService`·`WithdrawService`·`ChangePasswordService`·`LogoutService`·`ChangeProfileImageService`·`RemoveProfileImageService`)는 바깥 `@Transactional` 이 없다.** DB 쓰기는 `TransactionOperations` 의 짧은 트랜잭션(행 락 조회 → 변경 → `save`)이고 해싱·Redis·파일은 트랜잭션 밖에서 한다 — DB 커넥션을 쥔 채 외부 I/O 를 하지 않기 위해서다. 실패 시 fail-closed 순서는 각 서비스 KDoc 에 있다
- **커밋 후 콜백은 예외를 던지지 않는다.** `registerSynchronization` 으로 등록한 `afterCommit` 은 던지면 뒤 콜백이
  건너뛰어지고 커밋된 요청이 실패로 보인다. `@TransactionalEventListener(AFTER_COMMIT)` 리스너는 afterCompletion 에서
  돌아 Spring 이 예외를 로그만 남기고 삼키지만(`docs/decisions/concurrency.md`), 실패가 조용히 묻히므로 실패할 수 있는 일(전송·실행기에 올리기·DB/IO)을 하는
  리스너·콜백은 직접 잡아서 로그를 남긴다. 맵에서 지우거나 future 를 취소하는 순수 인메모리 정리 리스너(`TableClosed` 정리 리스너들)는 잡지 않아도 된다. 다른 실행기를 기다리지도 않는다(`TableExecutor.call` 금지, `post`) — 그 스레드는 아직 JDBC 커넥션을 쥐고 있다. `SerialTableExecutor.call` 은 트랜잭션이 활성인 스레드(커밋 후 콜백 포함)에서 부르면 `IllegalStateException` 을 던진다 — 같은 테이블이면 인라인으로 돌아 이미 커밋된 트랜잭션에 합류해 쓰기가 사라지기 때문이다
