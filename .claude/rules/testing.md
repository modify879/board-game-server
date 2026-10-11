---
paths:
  - "src/test/**"
---

# 테스트

- 통합 테스트에는 `@Import(TestcontainersConfiguration::class)` 가 필요하다.
  빠뜨리면 "Failed to determine a suitable driver class" 로 컨텍스트가 뜨지 않는다
- 컨테이너가 필요한 테스트만 클래스 이름에 `IntegrationTest` 접미사를 붙인다
- 목 라이브러리를 쓰지 않는다. 인메모리 페이크를 테스트 파일 안에 만든다
- **페이크 이름에 테스트별 접두를 붙인다**(`RequestFakeWalletRepository`). top-level `private`
  클래스는 파일 안에서만 보이지만 이름은 패키지를 차지해, 같은 패키지의 두 테스트가 같은
  이름을 쓰면 `Redeclaration` 이다
- 예외는 메시지가 아니라 `errorCode` 로 검증한다
- 테스트 이름은 백틱을 쓴 한국어 문장
- 지갑을 열거나 착석하는 통합 테스트(wallet, holdem)는 `UserRepository` 로 **실제 `users` 행을 먼저 만든다**.
  `fk_wallets_user`·`fk_holdem_seats_user` 때문에 합성 userId 로 열면 FK 위반이다
- Postgres·Redis 컨테이너는 JVM 당 한 벌로 모든 통합 테스트가 공유한다 — 다른 테스트가 남긴 데이터에 기대지 않는다.
  이름은 `UniqueNames.suffix()`, Redis 등에 쓰는 합성 userId 는 실제 IDENTITY 와 겹치지 않는 대역(예: 1조부터)을 쓴다
- 규칙을 테스트로 못 박을 때는 성격이 다른 경로를 여럿 잡는다 — 사전 체크 경로만 타면
  DB 제약 번역이 한 번도 돌지 않은 채 테스트가 통과한다
- 홀덤 도메인은 `HandSimulationTest` 가 무작위 시뮬레이션·오라클·무늬 치환 메타모픽을 돈다. 실패 메시지의 시드는 `./gradlew unitTest -Dholdem.sim.seed=<시드>` 로 재생하고, 횟수는 `-Dholdem.sim.hands=<N>` 이다
