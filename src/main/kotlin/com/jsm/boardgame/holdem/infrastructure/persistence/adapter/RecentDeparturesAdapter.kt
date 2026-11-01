package com.jsm.boardgame.holdem.infrastructure.persistence.adapter

import com.jsm.boardgame.holdem.application.port.Departure
import com.jsm.boardgame.holdem.application.port.RecentDepartures
import com.jsm.boardgame.holdem.domain.model.Chips
import com.jsm.boardgame.holdem.domain.model.TableId
import com.jsm.boardgame.holdem.domain.model.UserId
import com.jsm.boardgame.holdem.infrastructure.persistence.entity.RejoinObligationId
import com.jsm.boardgame.holdem.infrastructure.persistence.entity.RejoinObligationJpaEntity
import com.jsm.boardgame.holdem.infrastructure.persistence.entity.RejoinObligationJpaRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Repository
import java.time.Clock
import java.time.Duration

/**
 * 재착석 의무를 (사용자, 테이블)당 1행으로 보관한다. 호출자(기립·정산)의 트랜잭션에 합류하므로 기립과 함께 커밋·롤백된다.
 * 만료된 행은 읽을 때 없는 것으로 취급할 뿐 지우지 않는다 — 같은 (사용자, 테이블)의 다음 [record] 가 덮어쓴다.
 */
@Repository
class RecentDeparturesAdapter(
    private val jpa: RejoinObligationJpaRepository,
    private val clock: Clock,
    @Value("\${app.holdem.rejoin-obligation-window}") private val window: Duration,
) : RecentDepartures {

    override fun record(userId: UserId, tableId: TableId, amount: Chips) {
        val key = RejoinObligationId(userId.value, tableId.value)
        if (amount.isZero()) {
            jpa.deleteById(key)
            return
        }
        val expiresAt = clock.instant().plus(window)
        val entity = jpa.findById(key).orElse(null)?.apply {
            this.amount = amount.amount
            this.expiresAt = expiresAt
        } ?: RejoinObligationJpaEntity(key, amount.amount, expiresAt)
        jpa.saveAndFlush(entity)
    }

    override fun obligationOf(userId: UserId, tableId: TableId): Departure? {
        val row = jpa.findById(RejoinObligationId(userId.value, tableId.value)).orElse(null) ?: return null
        if (!row.expiresAt.isAfter(clock.instant())) return null
        return Departure(Chips.reconstitute(row.amount), row.expiresAt)
    }

    override fun clear(userId: UserId) = jpa.deleteByKeyUserId(userId.value)
}
