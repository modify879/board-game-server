---
paths:
  - "**/infrastructure/persistence/**"
  - "src/main/resources/**"
---

# 영속성·스키마

- 유일성·존재는 DB 가 보장한다. 응용 계층의 `existsBy...` 는 친절한 오류용이고 동시 요청을 막지 못한다.
  이름 붙인 제약(`uk_users_username`, `fk_wallets_user`)이 실제 보장이고, 어댑터가 그 이름으로 번역한다
- **제약을 번역하는 어댑터는 `saveAndFlush` 를 쓴다.** `save()` 만 쓰면 UPDATE 가 커밋 시점에야
  flush 되어 CHECK 위반이 `catch` 를 지나친다. INSERT 는 IDENTITY 채번 때문에 즉시 flush 되어
  우연히 통과하므로 UPDATE 경로에서만 드러난다. 회귀 테스트는 UPDATE 경로로 잡는다.
- **assigned id 엔티티는 `Persistable` 로 새 행을 알린다.** PK 가 자동 채번이 아니라 애플리케이션이
  정하는 값이라 Spring Data 가 새 행인지 스스로 판단하지 못한다 — `Long = 0` 버전 필드로 판단하게
  하면 첫 저장도 `merge` 로 가고, Hibernate 가 없는 행의 merge 를 거부해 INSERT 가 전부 실패한다.
  `isNew()` 를 생성 시 `true`, 저장·조회 후 `false` 로 내리는 `Persistable` 이 이 자리를 대신한다
  (`WalletAdjustmentKeyJpaEntity`, `HandInProgressJpaEntity`)
- DB 타임아웃은 hikari `options`(`application.yaml`)로 커넥션마다: `lock_timeout=3s`·`statement_timeout=10s`·`idle_in_transaction_session_timeout=30s`.
  `TransientDataAccessException`(락·문장 타임아웃)은 503 `TEMPORARILY_UNAVAILABLE` — 롤백이 보장돼 재시도 안전. `TableExecutor.call` 에 호출자 타임아웃을 안 두는 이유는 `docs/decisions/concurrency.md`
- 문자열 컬럼은 `text`. 길이 제약이 DB 에 없으므로 VO 가 유일한 방어선이다
- 외부 리소스는 키만 저장하고 URL 은 presentation 이 조립한다
- 스키마 관리 — `ddl-auto: update` 를 계속 쓴다. Flyway 로 갈 계획이 없다.
  추가만 하고 아무것도 지우지 않으므로 컬럼 삭제·이름/타입 변경은 손으로 SQL 을 친다.
  Hibernate 가 못 만드는 제약(FK)은 `src/main/resources/data.sql` 의 멱등 DDL 로 건다.
  **`data.sql` 의 문장 구분자는 `;` 가 아니라 `@@@`** — 기본 분할기가 `do $$ ... $$` 안의 `;` 에서 자른다
