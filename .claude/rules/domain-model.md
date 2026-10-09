---
paths:
  - "**/domain/**"
  - "**/application/command/**"
---

# 도메인 모델 표준 형태

- VO 는 `of()` 팩토리에서 정규화하고, 실패하면 에러 코드를 가진 도메인 예외를 던진다.
  `require` 를 쓰지 않는다 — `IllegalArgumentException` 하나로는 클라이언트가 이유를 구분할 수 없다.
  길이는 `codePointCount` 로 센다(`String.length` 는 이모지를 2자로 센다)
- 매퍼는 `reconstitute()`, 그 외 전부 `of()`. `reconstitute()` 가 검증하지 않는 것은 의도다 —
  `of()` 로 복원하면 규칙을 조이는 순간 기존 데이터를 읽을 수 없게 된다
- 애그리거트의 식별자는 nullable. `null` = 아직 저장되지 않음. 센티넬을 쓰지 않는다
- Command 는 원시 타입만 받는다. enum 도 예외가 아니다 — 도메인 타입 변환은 서비스가 한다
  (`UserRole.of(command.role)`). `application` 이 계약이라 여기서 도메인 enum 을 받으면 상수명이
  곧 API 계약이 된다. `@RequestParam` 의 도메인 enum 은 그대로 둔다(입력 경계는 안전, 출력은 이미 `String`)
- 각 컨텍스트가 자기 ID value class 를 둔다(`TableId`, 컨텍스트별 `UserId` — 남의 domain 타입 금지, 규칙 1). 원시 타입은
  경계(Command·DTO·JPA·조회 경로·다른 컨텍스트가 받는 이벤트/API)에서만. ID value class 는 `toString()` 을 값으로 재정의한다 —
  기본값 `UserId(value=1)` 이 JWT subject·`convertAndSendToUser` 같은 문자열 경계로 새면 조용히 틀어진다
