package com.jsm.boardgame.holdem.domain.model

import com.jsm.boardgame.holdem.domain.exception.AlreadySeatedException
import com.jsm.boardgame.holdem.domain.exception.BuyInOutOfRangeException
import com.jsm.boardgame.holdem.domain.exception.InvalidTableNameException
import com.jsm.boardgame.holdem.domain.exception.NotSeatedException
import com.jsm.boardgame.holdem.domain.exception.SeatNoOutOfRangeException
import com.jsm.boardgame.holdem.domain.exception.SeatTakenException
import com.jsm.boardgame.holdem.domain.exception.HoldemErrorCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class HoldemTableTest {

    private fun newTable(): HoldemTable = HoldemTable.create("테이블")

    @Test
    fun `sitDown 하면 좌석에서 조회된다`() {
        val table = newTable()
        table.sitDown(seatNo = 1, userId = 1L, buyIn = Chips.of(10_000))

        assertEquals(1, table.seatOf(1L)?.seatNo)
        assertEquals(1L, table.seatAt(1)?.userId)
        assertEquals(listOf(1), table.occupiedSeats().map { it.seatNo })
    }

    @Test
    fun `standUp 하면 스택을 반환하고 좌석에서 사라진다`() {
        val table = newTable()
        table.sitDown(seatNo = 1, userId = 1L, buyIn = Chips.of(10_000))

        val returned = table.standUp(1L)

        assertEquals(Chips.of(10_000), returned)
        assertNull(table.seatOf(1L))
        assertEquals(emptyList(), table.occupiedSeats())
    }

    @Test
    fun `좌석 번호 0은 SEAT_NO_OUT_OF_RANGE 로 거부된다`() {
        val table = newTable()
        val e = assertFailsWith<SeatNoOutOfRangeException> {
            table.sitDown(seatNo = 0, userId = 1L, buyIn = Chips.of(10_000))
        }
        assertEquals(HoldemErrorCode.SEAT_NO_OUT_OF_RANGE, e.errorCode)
    }

    @Test
    fun `좌석 번호 10은 SEAT_NO_OUT_OF_RANGE 로 거부된다`() {
        val table = newTable()
        val e = assertFailsWith<SeatNoOutOfRangeException> {
            table.sitDown(seatNo = 10, userId = 1L, buyIn = Chips.of(10_000))
        }
        assertEquals(HoldemErrorCode.SEAT_NO_OUT_OF_RANGE, e.errorCode)
    }

    @Test
    fun `이미 점유된 좌석에 앉으면 SEAT_TAKEN 으로 거부된다`() {
        val table = newTable()
        table.sitDown(seatNo = 1, userId = 1L, buyIn = Chips.of(10_000))

        val e = assertFailsWith<SeatTakenException> {
            table.sitDown(seatNo = 1, userId = 2L, buyIn = Chips.of(10_000))
        }
        assertEquals(HoldemErrorCode.SEAT_TAKEN, e.errorCode)
    }

    @Test
    fun `같은 사용자가 두 번째 좌석에 앉으면 ALREADY_SEATED 로 거부된다`() {
        val table = newTable()
        table.sitDown(seatNo = 1, userId = 1L, buyIn = Chips.of(10_000))

        val e = assertFailsWith<AlreadySeatedException> {
            table.sitDown(seatNo = 2, userId = 1L, buyIn = Chips.of(10_000))
        }
        assertEquals(HoldemErrorCode.ALREADY_SEATED, e.errorCode)
    }

    @Test
    fun `8000 미만 바이인은 BUY_IN_OUT_OF_RANGE 로 거부된다`() {
        val table = newTable()
        val e = assertFailsWith<BuyInOutOfRangeException> {
            table.sitDown(seatNo = 1, userId = 1L, buyIn = Chips.of(7_900))
        }
        assertEquals(HoldemErrorCode.BUY_IN_OUT_OF_RANGE, e.errorCode)
    }

    @Test
    fun `20000 초과 바이인은 BUY_IN_OUT_OF_RANGE 로 거부된다`() {
        val table = newTable()
        val e = assertFailsWith<BuyInOutOfRangeException> {
            table.sitDown(seatNo = 1, userId = 1L, buyIn = Chips.of(20_100))
        }
        assertEquals(HoldemErrorCode.BUY_IN_OUT_OF_RANGE, e.errorCode)
    }

    @Test
    fun `정확히 8000 바이인은 허용된다`() {
        val table = newTable()
        val seat = table.sitDown(seatNo = 1, userId = 1L, buyIn = Chips.of(8_000))
        assertEquals(Chips.of(8_000), seat.stack)
    }

    @Test
    fun `정확히 20000 바이인은 허용된다`() {
        val table = newTable()
        val seat = table.sitDown(seatNo = 1, userId = 1L, buyIn = Chips.of(20_000))
        assertEquals(Chips.of(20_000), seat.stack)
    }

    @Test
    fun `앉은 적 없는 사용자의 standUp 은 NOT_SEATED 로 거부된다`() {
        val table = newTable()
        val e = assertFailsWith<NotSeatedException> { table.standUp(1L) }
        assertEquals(HoldemErrorCode.NOT_SEATED, e.errorCode)
    }

    @Test
    fun `markPresence 는 좌석의 연결 상태를 바꾼다`() {
        val table = newTable()
        table.sitDown(seatNo = 1, userId = 1L, buyIn = Chips.of(10_000))

        table.markPresence(1L, SeatPresence.DISCONNECTED)

        assertEquals(SeatPresence.DISCONNECTED, table.seatAt(1)?.presence)
    }

    @Test
    fun `앉은 적 없는 사용자의 markPresence 는 NOT_SEATED 로 거부된다`() {
        val table = newTable()
        val e = assertFailsWith<NotSeatedException> { table.markPresence(1L, SeatPresence.DISCONNECTED) }
        assertEquals(HoldemErrorCode.NOT_SEATED, e.errorCode)
    }

    @Test
    fun `공백 테이블 이름은 TABLE_NAME_INVALID 로 거부된다`() {
        val e = assertFailsWith<InvalidTableNameException> { HoldemTable.create("   ") }
        assertEquals(HoldemErrorCode.TABLE_NAME_INVALID, e.errorCode)
    }

    @Test
    fun `30 코드포인트를 넘는 테이블 이름은 TABLE_NAME_INVALID 로 거부된다`() {
        val longName = "😀".repeat(31)
        val e = assertFailsWith<InvalidTableNameException> { HoldemTable.create(longName) }
        assertEquals(HoldemErrorCode.TABLE_NAME_INVALID, e.errorCode)
    }

    @Test
    fun `moveButtonToNextOccupiedSeat 은 null 에서 최소 좌석으로, 이후 다음 좌석으로, 마지막에서 최소로 순환한다`() {
        val table = newTable()
        table.sitDown(seatNo = 2, userId = 1L, buyIn = Chips.of(10_000))
        table.sitDown(seatNo = 5, userId = 2L, buyIn = Chips.of(10_000))
        table.sitDown(seatNo = 9, userId = 3L, buyIn = Chips.of(10_000))

        table.moveButtonToNextOccupiedSeat()
        assertEquals(2, table.buttonSeatNo)

        table.moveButtonToNextOccupiedSeat()
        assertEquals(5, table.buttonSeatNo)

        table.moveButtonToNextOccupiedSeat()
        assertEquals(9, table.buttonSeatNo)

        table.moveButtonToNextOccupiedSeat()
        assertEquals(2, table.buttonSeatNo)
    }

    @Test
    fun `applyStacks 은 점유되지 않은 좌석 번호가 들어오면 예외를 던진다`() {
        val table = newTable()
        table.sitDown(seatNo = 1, userId = 1L, buyIn = Chips.of(10_000))

        assertFailsWith<IllegalStateException> {
            table.applyStacks(mapOf(1 to Chips.of(5_000), 7 to Chips.of(1_000)))
        }
    }

    @Test
    fun `applyStacks 은 일부 점유 좌석만 포함된 맵으로 정상 동작하고 나머지 좌석은 변경되지 않는다`() {
        val table = newTable()
        table.sitDown(seatNo = 1, userId = 1L, buyIn = Chips.of(10_000))
        table.sitDown(seatNo = 3, userId = 2L, buyIn = Chips.of(8_000))

        table.applyStacks(mapOf(1 to Chips.of(5_000)))

        assertEquals(Chips.of(5_000), table.seatAt(1)?.stack)
        assertEquals(Chips.of(8_000), table.seatAt(3)?.stack)
    }

    @Test
    fun `reconstitute 는 검증하지 않는다`() {
        val table = HoldemTable.reconstitute(
            id = TableId(1L),
            name = "   ",
            smallBlind = HoldemTable.SMALL_BLIND,
            bigBlind = HoldemTable.BIG_BLIND,
            buttonSeatNo = 42,
            seats = emptyMap(),
            version = 0,
        )
        assertEquals("   ", table.name)
        assertEquals(42, table.buttonSeatNo)
    }
}
