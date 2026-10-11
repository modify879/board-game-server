package com.jsm.boardgame.holdem.infrastructure.departure

import com.jsm.boardgame.holdem.application.port.Departure
import com.jsm.boardgame.holdem.application.port.RecentDepartures
import com.jsm.boardgame.holdem.domain.model.Chips
import com.jsm.boardgame.holdem.domain.model.UserId
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/**
 * ponytail: 인메모리라 재시작하면 사라진다 — 재시작 직후 한 번은 ratholing 이 가능하다. 그게 문제가 되면 DB 에 저장한다.
 * 만료된 항목은 읽을 때 없는 것으로 취급하고 치운다(사용자 수만큼만 쌓이므로 별도 청소는 두지 않는다).
 */
@Component
class InMemoryRecentDepartures(private val clock: Clock) : RecentDepartures {
    private val departures = ConcurrentHashMap<UserId, Departure>()

    override fun record(userId: UserId, amount: Chips) {
        if (amount.isZero()) {
            departures.remove(userId)
        } else {
            departures[userId] = Departure(amount, clock.instant().plus(WINDOW))
        }
    }

    override fun obligationOf(userId: UserId): Departure? {
        val departure = departures[userId] ?: return null
        if (!departure.expiresAt.isAfter(clock.instant())) {
            departures.remove(userId, departure)
            return null
        }
        return departure
    }

    companion object {
        private val WINDOW = Duration.ofMinutes(30)
    }
}
