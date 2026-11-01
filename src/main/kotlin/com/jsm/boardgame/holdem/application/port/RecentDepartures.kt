package com.jsm.boardgame.holdem.application.port

import com.jsm.boardgame.holdem.domain.model.Chips
import com.jsm.boardgame.holdem.domain.model.TableId
import com.jsm.boardgame.holdem.domain.model.UserId
import java.time.Instant

/**
 * 최근에 좌석을 떠난 사람이 가져간 금액 — 재착석 의무(ratholing 방지, 하우스 룰 7)의 근거다.
 * 떠난 그 테이블에 재착석 의무 기간(기본 30분, `app.holdem.rejoin-obligation-window`) 안에 다시 앉으면 [Departure.amount] 이상으로 앉아야 한다.
 * 의무는 (사용자, 테이블) 단위다 — 다른 테이블이나 새 테이블에는 적용되지 않는다.
 */
interface RecentDepartures {
    /** 그 테이블의 이전 기록을 덮어쓴다. [amount] 가 0 이면 그 테이블의 의무가 없어진다. */
    fun record(userId: UserId, tableId: TableId, amount: Chips)

    /** 만료됐거나 그 테이블에 기록이 없으면 null. */
    fun obligationOf(userId: UserId, tableId: TableId): Departure?

    /** 사용자의 만료되지 않은 의무 전부(테이블별). 로비 목록이 한 번에 읽는다. */
    fun obligationsOf(userId: UserId): Map<TableId, Departure>

    /** 사용자의 모든 테이블 의무를 지운다(QA 리셋). */
    fun clear(userId: UserId)
}

data class Departure(val amount: Chips, val expiresAt: Instant)
