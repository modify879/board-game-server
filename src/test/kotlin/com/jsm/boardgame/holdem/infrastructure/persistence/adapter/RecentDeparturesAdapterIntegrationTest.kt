package com.jsm.boardgame.holdem.infrastructure.persistence.adapter

import com.jsm.boardgame.TestcontainersConfiguration
import com.jsm.boardgame.holdem.domain.model.Chips
import com.jsm.boardgame.holdem.domain.model.UserId
import com.jsm.boardgame.holdem.infrastructure.persistence.entity.RecentDepartureJpaRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private class RecentDeparturesMutableClock(var now: Instant) : Clock() {
    override fun getZone() = ZoneOffset.UTC
    override fun withZone(zone: ZoneId?) = this
    override fun instant(): Instant = now
}

@SpringBootTest
@Import(TestcontainersConfiguration::class)
class RecentDeparturesAdapterIntegrationTest {

    @Autowired
    private lateinit var jpa: RecentDepartureJpaRepository

    private val start = Instant.parse("2026-10-11T00:00:00Z")
    private val clock = RecentDeparturesMutableClock(start)
    private val departures by lazy { RecentDeparturesAdapter(jpa, clock, Duration.ofMinutes(30)) }

    // FK 가 없는 합성 userId — 다른 테스트의 실제 IDENTITY 와 겹치지 않는 대역(1조부터)
    private val user = UserId(1_000_000_000_000L + ids.getAndIncrement())

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
    fun `30분이 지나면 만료된 의무는 없는 것으로 보고 직전까지는 있다`() {
        departures.record(user, Chips.of(9_000))

        clock.now = start.plus(Duration.ofMinutes(30)).minusSeconds(1)
        assertEquals(Chips.of(9_000), departures.obligationOf(user)!!.amount)

        clock.now = start.plus(Duration.ofMinutes(30))
        assertNull(departures.obligationOf(user))
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
    fun `만료된 행 위에 다시 기록해도 새 의무가 생긴다`() {
        departures.record(user, Chips.of(9_000))
        clock.now = start.plus(Duration.ofHours(1))

        departures.record(user, Chips.of(7_000))

        assertEquals(Chips.of(7_000), departures.obligationOf(user)!!.amount)
    }

    @Test
    fun `0 을 기록하면 의무가 지워지고 없는 행에 0 을 기록해도 실패하지 않는다`() {
        departures.record(user, Chips.of(9_000))

        departures.record(user, Chips.ZERO)
        departures.record(user, Chips.ZERO)

        assertNull(departures.obligationOf(user))
    }

    private companion object {
        val ids = AtomicLong()
    }
}
