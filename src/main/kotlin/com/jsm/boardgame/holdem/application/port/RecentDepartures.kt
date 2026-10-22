package com.jsm.boardgame.holdem.application.port

import com.jsm.boardgame.holdem.domain.model.Chips
import com.jsm.boardgame.holdem.domain.model.UserId
import java.time.Instant

/**
 * 최근에 좌석을 떠난 사람이 가져간 금액 — 재착석 의무(ratholing 방지, 하우스 룰 7)의 근거다.
 * 어느 테이블에서든 떠난 뒤 재착석 의무 기간(기본 30분, `app.holdem.rejoin-obligation-window`) 안에 다시 앉으면 [Departure.amount] 이상으로 앉아야 한다.
 */
interface RecentDepartures {
    /** 이전 기록을 덮어쓴다. [amount] 가 0 이면 의무가 없어진다. */
    fun record(userId: UserId, amount: Chips)

    /** 만료됐거나 기록이 없으면 null. */
    fun obligationOf(userId: UserId): Departure?
}

data class Departure(val amount: Chips, val expiresAt: Instant)
