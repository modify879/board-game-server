package com.jsm.boardgame.wallet.application.port

import java.time.Instant

/** 이미 처리한 관리자 조정 요청 키. 같은 키의 두 번째 요청을 DB 유일성으로 막는다. */
interface AdjustmentKeyRegistry {
    /** 키를 선점한다. 이미 있으면 AdjustmentAlreadyAppliedException. 호출자의 트랜잭션에 묶인다. */
    fun claim(key: String, adminUserId: Long, targetUserId: Long, at: Instant)
}
