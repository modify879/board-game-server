---
paths:
  - "src/test/**"
---

# 테스트

- 통합 테스트는 `@IntegrationTest` 하나만 붙인다(SpringBootTest·MockMvc·Testcontainers 를 묶은 메타 애너테이션).
  컨텍스트 키를 바꾸는 애너테이션(`@TestPropertySource`·`@ActiveProfiles`·중첩 `@TestConfiguration`·`@Import` 추가 설정·
  `@DynamicPropertySource`)은 빈을 갈아끼워야 할 때만 덧붙이고, 테스트 전체에 걸친 값은 build.gradle.kts 의 `systemProperty` 로 준다.
  컨텍스트가 하나 늘 때마다 공유 Postgres 에 풀을 하나 더 쥐고, max_connections 를 넘으면 컨텍스트가 30초 대기 뒤 실패한다
- 예외: `QaProfileGuardMissingMarkerIntegrationTest` 는 `@IntegrationTest` 없이 자기 컨테이너와 `SpringApplicationBuilder` 로 기동한다 — 표식 없는 DB 에서 시작해야 하기 때문이다
- 컨테이너가 필요한 테스트만 클래스 이름에 `IntegrationTest` 접미사를 붙인다
- 목 라이브러리를 쓰지 않는다. 인메모리 페이크를 쓴다. 한 테스트만 쓰는 건 그 테스트 파일 안에, 여러 테스트가 같이 쓰는 건 포트의 테스트 패키지에 `Fake<Port>.kt` 로 둔다(접두 없음)
- **테스트 파일 안의 페이크 이름에는 테스트별 접두를 붙인다**(`RequestFakeWalletRepository`). top-level `private`
  클래스는 파일 안에서만 보이지만 이름은 패키지를 차지해, 같은 패키지의 두 테스트가 같은
  이름을 쓰면 `Redeclaration` 이다
- 예외는 메시지가 아니라 `errorCode` 로 검증한다
- 테스트 이름은 백틱을 쓴 한국어 문장
- 지갑을 열거나 착석하는 통합 테스트(wallet, holdem)는 **실제 `users` 행(`UserRepository` 또는 가입 API)을 먼저 만든다**.
  `fk_wallets_user`·`fk_holdem_seats_user` 때문에 합성 userId 로 열면 FK 위반이다
- Postgres·Redis 컨테이너는 JVM 당 한 벌로 모든 통합 테스트가 공유한다 — 다른 테스트가 남긴 데이터에 기대지 않는다.
  이름은 `UniqueNames.suffix()`, Redis 등에 쓰는 합성 userId 는 실제 IDENTITY 와 겹치지 않는 대역(예: 1조부터)을 쓴다
- 규칙을 테스트로 못 박을 때는 성격이 다른 경로를 여럿 잡는다 — 사전 체크 경로만 타면
  DB 제약 번역이 한 번도 돌지 않은 채 테스트가 통과한다
- 홀덤 도메인은 `HandSimulationTest` 가 무작위 시뮬레이션·오라클·무늬 치환 메타모픽을 돈다. 실패 메시지의 시드는 `./gradlew unitTest -Dholdem.sim.seed=<시드>` 로 재생하고, 횟수는 `-Dholdem.sim.hands=<N>` 이다
