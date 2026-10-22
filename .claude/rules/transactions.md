---
paths:
  - "**/application/**"
  - "**/infrastructure/**"
  - "**/presentation/ws/**"
---

# 트랜잭션·락

- **돈을 잠그는 순서는 항상 요청·테이블 → 지갑이다.** 홀덤 테이블 → 지갑, 충전·환전 요청 → 지갑
  순으로 잠근다. 지갑을 먼저 잠근 뒤 요청 행이나 홀덤 테이블을 잠그지 않는다 — 순서를 뒤집으면
  데드락이다.
- **같은 트랜잭션에 참여한 `@Transactional` 프록시를 예외가 한 번이라도 넘으면 바깥에서 잡아도 트랜잭션은 rollback-only 다.**
  커밋 시점에 `UnexpectedRollbackException` 으로 터진다. 실패를 격리해야 하면 호출 단위로 `REQUIRES_NEW` 를 쓴다(`AdmitJoinRequestService`)
- **user 의 `LoginService`·`RefreshTokenService`·`ChangeUserRoleService` 는 바깥 `@Transactional` 이 없다.** DB 쓰기는 `save` 가 자체로 짧게 커밋하고 Redis 는 트랜잭션 밖에서 부른다 — DB 커넥션을 쥔 채 외부 I/O 를 하지 않기 위해서다. 실패 시 fail-closed 순서는 각 서비스 KDoc 에 있다
- **커밋 후 콜백(`afterCommit`, AFTER_COMMIT 리스너)은 예외를 던지지 않는다** — 뒤 콜백이 건너뛰어지고 커밋된 요청이 실패로
  보인다. 잡아서 로그. 다른 실행기를 기다리지도 않는다(`TableExecutor.call` 금지, `post`) — 그 스레드는 아직 JDBC 커넥션을 쥐고 있다. `SerialTableExecutor.call` 은 트랜잭션이 활성인 스레드(커밋 후 콜백 포함)에서 부르면 `IllegalStateException` 을 던진다 — 같은 테이블이면 인라인으로 돌아 이미 커밋된 트랜잭션에 합류해 쓰기가 사라지기 때문이다
