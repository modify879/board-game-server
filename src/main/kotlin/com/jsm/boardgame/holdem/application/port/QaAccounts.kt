package com.jsm.boardgame.holdem.application.port

/** QA 계정 리셋이 user·wallet 컨텍스트에서 필요로 하는 것. qa 프로필 전용이다. */
interface QaAccounts {
    /** 없는(또는 탈퇴한) 아이디면 null. */
    fun userIdOf(username: String): Long?

    /** 대기(PENDING) 중인 충전·환전 요청을 모두 반려한다. 환전 보류금도 지갑 쪽 반려 경로가 푼다. */
    fun rejectPendingRequests(userId: Long, adminUserId: Long, reason: String)

    /** 잔액을 [delta] 만큼 증감한다(부호 있음). */
    fun adjustBalance(userId: Long, delta: Long, adminUserId: Long, reason: String)
}
