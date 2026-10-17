package com.jsm.boardgame.holdem.infrastructure.persistence.adapter

import com.jsm.boardgame.holdem.application.port.Departure
import com.jsm.boardgame.holdem.application.port.RecentDepartures
import com.jsm.boardgame.holdem.domain.model.Chips
import com.jsm.boardgame.holdem.domain.model.UserId
import com.jsm.boardgame.holdem.infrastructure.persistence.entity.RecentDepartureJpaEntity
import com.jsm.boardgame.holdem.infrastructure.persistence.entity.RecentDepartureJpaRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Repository
import java.time.Clock
import java.time.Duration

/**
 * 재착석 의무를 사용자당 1행으로 보관한다. 호출자(기립·정산)의 트랜잭션에 합류하므로 기립과 함께 커밋·롤백된다.
 * 만료된 행은 읽을 때 없는 것으로 취급할 뿐 지우지 않는다 — 사용자당 한 행이고 다음 [record] 가 덮어쓴다.
 */
@Repository
class RecentDeparturesAdapter(
    private val jpa: RecentDepartureJpaRepository,
    private val clock: Clock,
    @Value("\${app.holdem.rejoin-obligation-window}") private val window: Duration,
) : RecentDepartures {

    override fun record(userId: UserId, amount: Chips) {
        if (amount.isZero()) {
            jpa.deleteById(userId.value)
            return
        }
        val expiresAt = clock.instant().plus(window)
        val entity = jpa.findById(userId.value).orElse(null)?.apply {
            this.amount = amount.amount
            this.expiresAt = expiresAt
        } ?: RecentDepartureJpaEntity(userId.value, amount.amount, expiresAt)
        jpa.saveAndFlush(entity)
    }

    override fun obligationOf(userId: UserId): Departure? {
        val row = jpa.findById(userId.value).orElse(null) ?: return null
        if (!row.expiresAt.isAfter(clock.instant())) return null
        return Departure(Chips.of(row.amount), row.expiresAt)
    }
}
