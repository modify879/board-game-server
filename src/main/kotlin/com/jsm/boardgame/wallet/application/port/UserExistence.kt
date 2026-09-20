package com.jsm.boardgame.wallet.application.port

/**
 * 조정 대상이 실재하는 사용자인지 확인한다. wallet 이 user 에 대해 아는 것은 이 Boolean 하나뿐이다.
 *
 * user 의 서비스를 직접 부르지 않고 이 포트를 거치는 이유는 규칙 8 이다 —
 * 직접 부르면 실패가 user 컨텍스트의 errorCode 로 나가는데, 깨진 규칙은 "조정 대상이 유효해야 한다"
 * 라는 wallet 의 규칙이다.
 */
fun interface UserExistence {
    fun exists(userId: Long): Boolean
}
