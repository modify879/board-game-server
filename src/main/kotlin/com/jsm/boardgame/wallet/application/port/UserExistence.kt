package com.jsm.boardgame.wallet.application.port

import com.jsm.boardgame.wallet.domain.model.UserId

/**
 * 사용자가 실재하고 탈퇴하지 않았는지(활성) 확인한다. wallet 이 user 에 대해 아는 것은 이 Boolean 하나뿐이다.
 * 바이인·충전 요청·환전 요청·관리자 조정이 지갑 락을 잡은 **뒤에** 부른다(회원 탈퇴와의 직렬화, wallet rules).
 *
 * user 의 서비스를 직접 부르지 않고 이 포트를 거치는 이유는 규칙 8 이다 —
 * 직접 부르면 실패가 user 컨텍스트의 errorCode 로 나가는데, 깨진 규칙은 "대상 사용자가 활성이어야 한다"
 * 라는 wallet 의 규칙이다.
 */
fun interface UserExistence {
    fun exists(userId: UserId): Boolean
}
