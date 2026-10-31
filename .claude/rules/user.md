---
paths: ["**/user/**"]
---

# user

## 이 컨텍스트에서만 지킬 것

- 액세스 토큰은 JWT, 리프레시 토큰은 불투명하다. 통일하지 않는다. 리프레시 토큰에서 userId 를
  유도할 수 있게 만들지 않는다(`auth:refresh:{sha256}` 인덱스로만). 그 인덱스는 회전해도 지우지 않고
  TTL 로만 소멸시킨다 — 지우면 재사용 탐지가 무력화된다
- 유예 안에서 직전 리프레시 토큰이 다시 오면 다시 회전하지 않고 처음 회전의 후속 토큰을 그대로 재생한다(`RotationResult.Replayed`).
  경과 시간 가드를 되살리지 않는다 — 동시 갱신이 세션 폐기로 이어진다. 재생 레코드는 제시된 토큰에서 유도한 키로 암호화한다
- 역할 변경은 액세스 토큰 블랙리스트 + 갱신으로 반영한다. 리프레시 토큰은 살려둔다.
  최초 관리자 한 명만 DB 로 직접 만들고, 이후는 `POST /api/admin/users/{id}/role` 을 쓴다
- 로그인 응답 시간이 아이디 존재 여부를 노출한다 — 더미 해시 방어는 `3e4d64e` 에서 의도적으로
  제거했다. 되살리려면 위협 모델부터 정해라
- 세션은 사용자당 하나라 탭 간 토큰 공유는 클라이언트가 한다. 서버에 탭별 세션을 만들지 않는다

- 프로필 이미지는 로컬 디스크(`app.profile-image.storage-dir`)에 둔다. 형식은 매직 바이트로만 판별하고(클라이언트 Content-Type·파일명 불신) 2MB 가 상한이다.
  파일과 DB 는 원자적이지 않아 고아 파일이 남을 수 있다 — 순서(저장 → DB → 옛 파일 삭제)는 `ChangeProfileImageService` KDoc.
  사진이 없으면 `profileImageUrl` 은 null 이다. 기본 이미지는 클라이언트가 그린다 — 서버에 기본 이미지 자산이나 기본 URL 설정을 두지 않는다
- 탈퇴(`withdrawn_at`)한 사용자는 어댑터에서 걸러낸다 — 명령 쪽 `findById`·`findByIdForUpdate`·`findByUsername` 과 조회 쪽 전부. 서비스마다 확인하지 않는다.
  사용자명은 계속 예약되고(`existsByUsername` 은 탈퇴자도 센다) 닉네임은 `withdrawn-user-{id}` 묘비로 바꿔 반납한다(15자 이상이라 유효 닉네임과 겹치지 않는다)
- 마지막 관리자의 자기 강등을 막지 않는다(사용자 결정)
- **사용자를 바꾸는 명령은 전부 `findByIdForUpdate` 로 읽고 같은 짧은 트랜잭션에서 `save` 한다.** 트랜잭션 밖에서 읽은 `User` 를 저장하지 않는다 — `save` 는 행 전체를 덮어써
  그사이 커밋된 변경(새 비밀번호, 탈퇴)을 되돌린다. 해싱·Redis·파일은 트랜잭션 밖. 근거는 `docs/decisions/concurrency.md` "사용자 행도 비관적 락"
- 탈퇴의 사전 조건과 저장은 트랜잭션 하나다(`WithdrawService`, `TransactionOperations`). 안에서 **지갑 락(`WalletHoldings`) → 좌석·대기열 확인 → 사용자 행 락 재조회(해시가 확인한 것과 다르면 `CURRENT_PASSWORD_MISMATCH`) → 저장** 순서를 지킨다.
  좌석 확인을 락 앞으로 옮기면 그 사이 커밋된 착석을 놓쳐 앉은 채 탈퇴된다(`WithdrawConcurrencyIntegrationTest`). 세션 종료는 커밋 뒤다
