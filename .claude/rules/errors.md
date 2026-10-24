---
paths:
  - "**/exception/**"
  - "**/common/error/**"
  - "**/common/web/**"
  - "**/presentation/**"
  - "**/infrastructure/security/**"
---

# 오류 계약

- 예외는 그 실패를 소유한 계층에 둔다. 기준은 "애그리거트가 그 단어를 아는가" —
  `User` 에 세션·토큰이 없으므로 `LoginFailedException` 은 `application/exception` 이고,
  `RawPassword` 가 던지는 `InvalidPasswordException` 은 `domain/exception` 이다
- 반면 에러 코드 enum 은 계층이 아니라 컨텍스트당 하나다. 쪼개면 예외를 한 계층 옮기는
  리팩터링이 API 변경이 된다. `common` 에는 기반 타입(`BusinessException`)과 `CommonErrorCode` 까지만
- `ErrorCode` 는 `HttpStatus` 를 모른다. 도메인은 `ErrorKind` 까지만 알고 상태 매핑은 핸들러가 한다
- 응답에는 `errorCode`, 로그에는 `logMessage`, 둘은 `traceId` 로 잇는다. `detail` 은 비어 있는 게 정상
- 4xx 는 WARN 스택 없이, 5xx 는 ERROR 스택 포함
- **시큐리티 필터 단계의 401·403 도 같은 계약을 따른다.** `@RestControllerAdvice` 를 안 거치므로
  `AuthenticationEntryPoint`/`AccessDeniedHandler` 를 `exceptionHandling` 과 `oauth2ResourceServer`
  **양쪽에** 등록해야 한다. 한쪽만 하면 토큰이 있고 검증에 실패한 경우가 빠진다
- 남의 리소스는 403 이 아니라 404 다. 서비스가 조회 단계에서 소유자까지 확인한다.
  도메인의 소유자 검사는 불변식으로 남긴다 — "안 쓰이는 코드" 로 보고 지우지 않는다
