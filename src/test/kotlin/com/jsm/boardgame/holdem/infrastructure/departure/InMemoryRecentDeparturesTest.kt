package com.jsm.boardgame.holdem.infrastructure.departure

import com.jsm.boardgame.holdem.domain.model.Chips
import com.jsm.boardgame.holdem.domain.model.UserId
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private class MutableClock(var now: Instant) : Clock() {
    override fun getZone() = ZoneOffset.UTC
    override fun withZone(zone: java.time.ZoneId?) = this
    override fun instant(): Instant = now
}

class InMemoryRecentDeparturesTest {

    private val start = Instant.parse("2026-10-11T00:00:00Z")
    private val clock = MutableClock(start)
    private val departures = InMemoryRecentDepartures(clock)
    private val user = UserId(1L)

    @Test
    fun `기록이 없으면 의무도 없다`() {
        assertNull(departures.obligationOf(user))
    }

    @Test
    fun `떠난 금액과 30분 뒤 만료 시각을 돌려준다`() {
        departures.record(user, Chips.of(9_000))

        val departure = departures.obligationOf(user)!!
        assertEquals(Chips.of(9_000), departure.amount)
        assertEquals(start.plus(Duration.ofMinutes(30)), departure.expiresAt)
    }

    @Test
    fun `30분이 지나면 만료된 의무는 없는 것으로 본다`() {
        departures.record(user, Chips.of(9_000))

        clock.now = start.plus(Duration.ofMinutes(30))

        assertNull(departures.obligationOf(user))
    }

    @Test
    fun `만료 직전에는 아직 의무가 있다`() {
        departures.record(user, Chips.of(9_000))

        clock.now = start.plus(Duration.ofMinutes(30)).minusSeconds(1)

        assertEquals(Chips.of(9_000), departures.obligationOf(user)!!.amount)
    }

    @Test
    fun `새 기록은 이전 기록을 덮어쓰고 창도 새로 시작한다`() {
        departures.record(user, Chips.of(9_000))
        clock.now = start.plus(Duration.ofMinutes(20))

        departures.record(user, Chips.of(5_000))

        val departure = departures.obligationOf(user)!!
        assertEquals(Chips.of(5_000), departure.amount)
        assertEquals(clock.now.plus(Duration.ofMinutes(30)), departure.expiresAt)
    }

    @Test
    fun `0 을 기록하면 의무가 지워진다`() {
        departures.record(user, Chips.of(9_000))

        departures.record(user, Chips.ZERO)

        assertNull(departures.obligationOf(user))
    }
}
