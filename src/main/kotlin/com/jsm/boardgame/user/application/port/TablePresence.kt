package com.jsm.boardgame.user.application.port

import com.jsm.boardgame.user.domain.model.UserId

/** 탈퇴 사전 조건. user 가 홀덤에 대해 아는 것은 이 Boolean 하나뿐이다. */
fun interface TablePresence {
    /** 좌석에 앉아 있거나 참가 대기열에 있는가. */
    fun isAtAnyTable(userId: UserId): Boolean
}
