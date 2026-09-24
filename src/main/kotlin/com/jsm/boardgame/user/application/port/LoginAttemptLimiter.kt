package com.jsm.boardgame.user.application.port

import com.jsm.boardgame.user.domain.model.Username
import java.time.Duration

/** 로그인 실패 횟수를 센다(순수 카운터). 계정 잠금 자체는 `User.lockedAt` 이 갖는다 — 이 포트는 관여하지 않는다. */
interface LoginAttemptLimiter {
    /** 실패 1회를 센다. 이번 실패로 [maxFailures] 에 도달했으면 true(카운터는 지워진다). */
    fun recordFailure(username: Username, maxFailures: Int, window: Duration): Boolean

    fun reset(username: Username)
}
