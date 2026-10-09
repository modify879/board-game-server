## 예외와 오류 계약

### 예외를 계층별로 두고 에러 코드는 컨텍스트당 하나인 이유
도메인 불변식 위반이 domain 예외라는 데는 문헌상 이견이 없다
(MS Learn: *"invariants enforcement is the responsibility of the domain entities"*).
`presentation` 이 자기 예외를 갖는 것도 같다 — *"that model is meant to be a ViewModel or DTO
and that's an MVC or API concern **not a domain model concern**."*

반면 에러 코드까지 계층별로 쪼개면 **클라이언트가 보는 계약이 패키지 구조를 따라 흔들린다.**
예외를 한 계층 옮기는 리팩터링이 API 변경이 되어선 안 된다.

`XxxNotFoundException` 을 domain 에 두는 것은 **문헌이 실제로 갈리는 지점**이라 그대로 둔다
("존재하지 않음"이 도메인 불변식인지 유스케이스 관심사인지 합의가 없다).

### `common` 에 범용 예외를 두지 않는 이유
타입이 아니라 메시지 문자열이 의미를 나르게 되어 아이디 중복인지 닉네임 중복인지 구분할 수 없다.
기반 타입(`BusinessException`)을 공유하는 것은 표준 패턴이므로 문제가 아니다.
