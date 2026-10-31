---
paths:
  - "**/presentation/ws/**"
  - "**/common/web/stomp/**"
  - "**/common/config/WebSocket*"
---

# WebSocket·STOMP

- 개인 채널은 `/user/queue/...` 로만 구독한다. 원시 `/queue` 구독은 `StompDestinationGuard` 가 막는다 —
  열어두면 세션 id 만 알아도 남의 개인 큐를 엿본다
- join-queue 목적지(`/user/queue/holdem/join-queue`)는 `HoldemSubscriptionInterceptor` 가 인증 여부만 본다. 인증 없는 SUBSCRIBE 는 ERROR 프레임을 받는다.
- 구독 인가는 두 겹이다. `StompDestinationGuard` 는 모든 SUBSCRIBE 에서 패턴 문자(`*?{}`)와 원시 `/queue` 를
  거부한다. `HoldemSubscriptionInterceptor` 는 홀덤 테이블 목적지로 보이는 것(`/topic/tables/`,
  `/user/queue/tables/`)만 대상으로, 그중 id 를 해석하지 못하면 거부한다(fail-closed) — 그 밖의 목적지는
  통과시킨다. 형식만 비튼 테이블 목적지로 인가를 건너뛰지 못하게 하려는 것이다
- 인가 실패 중 경합으로 생길 수 있는 것(개인 큐 착석 검사)은 ERROR 대신 SUBSCRIBE 를 버린다(null) — ERROR 는
  연결을 끊는다. 형식 오류는 그대로 ERROR
- **CONNECT 프레임에서 `StompHeaderAccessor.wrap(message)` 로 `setUser` 하지 않는다.** `wrap` 은 새 인스턴스를
  만들어 `StompSubProtocolHandler` 가 건 `setUserChangeCallback` 이 없다 — 세션에 반영되지 않아 이후 모든
  프레임에서 principal 이 null 이다. `MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor::class.java)`
  로 붙어 있는 accessor 를 쓴다. 읽기만 할 때는 `wrap` 도 무방하다
- **`convertAndSendToUser` 는 대상 세션이 없으면 예외도 로그도 없이 버린다.** `SimpUserRegistry` 로 확인해
  WARN 을 남기되 **전송은 그대로 한다**(레지스트리가 경합할 수 있어 안 보내면 오히려 진짜 유실이 생긴다)
  예외 — 특정 세션을 겨냥한 전송(sessionId 헤더, 예: join-queue 구독 스냅샷)은 그 세션이 레지스트리에 없으면 보내지 않고 WARN 만 남긴다. 보내면 Spring 이 사용자의 모든 세션으로 퍼뜨린다.
- **브로드캐스트는 커밋 전에 내보내지 않는다.** 순번은 `BEFORE_COMMIT` 에서 따고 전송은 `afterCommit` 에서 한다(세부는 holdem rules).
  커밋 전에 내보내면 뒤이어 롤백됐을 때 클라이언트만 서버보다 앞선 상태를 보게 된다
