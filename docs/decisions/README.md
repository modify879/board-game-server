# 설계 결정과 근거

설계 결정의 "왜"를 담는다. 규칙 자체는 `CLAUDE.md`·`.claude/rules/` 에 있다.
자동으로 로드되지 않으므로, 결정을 뒤집으려 할 때 읽으면 된다.
규칙을 바꾸기 전에 여기서 그 항목을 먼저 찾아라. 대부분은 이미 한 번 다른 방식으로 시도했다가 되돌린 것이다.

| 주제 | 파일 |
|---|---|
| 계층과 포트 | `docs/decisions/layers-and-ports.md` |
| 예외와 오류 계약 | `docs/decisions/errors.md` |
| 경계에서의 타입 | `docs/decisions/boundary-types.md` |
| 도메인 모델 | `docs/decisions/domain-model.md` |
| 동시성 | `docs/decisions/concurrency.md` |
| 인증 | `docs/decisions/auth.md` |
| 홀덤 하우스 룰 | `docs/decisions/holdem-house-rules.md` |
| 홀덤 구현 | `docs/decisions/holdem.md` |

## 하지 않기로 한 것

- **DevTools**: 오래 떠 있는 서버를 승인 없이 재시작시키고, 개발용 기본값으로 오류 응답 형태를 바꾼다.
- **Micrometer Tracing**: 서버 한 대 설계이고, 요청 단위 로그 상관은 `RequestIdFilter` 가 이미 한다 — 요청마다 8자리 `traceId` 를 만들어 MDC 와 `X-Trace-Id` 응답 헤더에 싣고(시큐리티보다 먼저 실행), 오류 응답·로그가 같은 값으로 이어진다.
  서비스가 여럿이 되거나 트레이싱 백엔드에서 스팬별 지연이 필요해지면 그때 도입한다.
