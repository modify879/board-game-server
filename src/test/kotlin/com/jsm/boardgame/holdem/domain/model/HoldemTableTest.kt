package com.jsm.boardgame.holdem.domain.model

import com.jsm.boardgame.holdem.domain.exception.AlreadySeatedException
import com.jsm.boardgame.holdem.domain.exception.BuyInOutOfRangeException
import com.jsm.boardgame.holdem.domain.exception.ChipAddOutOfRangeException
import com.jsm.boardgame.holdem.domain.exception.InvalidTableNameException
import com.jsm.boardgame.holdem.domain.exception.NotSeatedException
import com.jsm.boardgame.holdem.domain.exception.TableFullException
import com.jsm.boardgame.holdem.domain.exception.TableNotEmptyException
import com.jsm.boardgame.holdem.domain.exception.HoldemErrorCode
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HoldemTableTest {

    private fun newTable(): HoldemTable = HoldemTable.create("테이블", TestTablePolicy)

    @Test
    fun `sitDown 하면 좌석에서 조회된다`() {
        val table = newTable()
        table.sitDown(userId = UserId(1L), buyIn = Chips.of(10_000))

        assertEquals(1, table.seatOf(UserId(1L))?.seatNo)
        assertEquals(UserId(1L), table.seatAt(1)?.userId)
        assertEquals(listOf(1), table.occupiedSeats().map { it.seatNo })
    }

    @Test
    fun `standUp 하면 스택을 반환하고 좌석에서 사라진다`() {
        val table = newTable()
        table.sitDown(userId = UserId(1L), buyIn = Chips.of(10_000))

        val returned = table.standUp(UserId(1L))

        assertEquals(Chips.of(10_000), returned)
        assertNull(table.seatOf(UserId(1L)))
        assertEquals(emptyList(), table.occupiedSeats())
    }

    @Test
    fun `같은 사용자가 두 번째 좌석에 앉으면 ALREADY_SEATED 로 거부된다`() {
        val table = newTable()
        table.sitDown(userId = UserId(1L), buyIn = Chips.of(10_000))

        val e = assertFailsWith<AlreadySeatedException> {
            table.sitDown(userId = UserId(1L), buyIn = Chips.of(10_000))
        }
        assertEquals(HoldemErrorCode.ALREADY_SEATED, e.errorCode)
    }

    @Test
    fun `1,3번 좌석이 점유되어 있으면 다음 sitDown 은 2번에 앉는다`() {
        val table = HoldemTable.reconstitute(
            id = TableId(1),
            name = "t",
            policy = TestTablePolicy,
            buttonSeatNo = null,
            seats = mapOf(
                1 to Seat.reconstitute(1, UserId(1L), Chips.of(10_000), SeatPresence.SEATED),
                3 to Seat.reconstitute(3, UserId(3L), Chips.of(10_000), SeatPresence.SEATED),
            ),
        )

        val seat = table.sitDown(userId = UserId(99L), buyIn = Chips.of(10_000))

        assertEquals(2, seat.seatNo)
    }

    @Test
    fun `빈 좌석이 없으면 TABLE_FULL 로 거부된다`() {
        val table = newTable()
        repeat(HoldemTable.MAX_SEATS) { i -> table.sitDown(userId = UserId((i + 1).toLong()), buyIn = Chips.of(10_000)) }

        val e = assertFailsWith<TableFullException> {
            table.sitDown(userId = UserId(10L), buyIn = Chips.of(10_000))
        }
        assertEquals(HoldemErrorCode.TABLE_FULL, e.errorCode)
    }

    @Test
    fun `hasEmptySeat 는 좌석이 남아 있으면 true, 9석이 다 차면 false 다`() {
        val table = newTable()
        assertTrue(table.hasEmptySeat())
        repeat(HoldemTable.MAX_SEATS) { i -> table.sitDown(userId = UserId((i + 1).toLong()), buyIn = Chips.of(10_000)) }
        assertFalse(table.hasEmptySeat())
    }

    @Test
    fun `20BB 미만 바이인은 BUY_IN_OUT_OF_RANGE 로 거부된다`() {
        val table = newTable()
        val e = assertFailsWith<BuyInOutOfRangeException> {
            table.sitDown(userId = UserId(1L), buyIn = Chips.of(3_900))
        }
        assertEquals(HoldemErrorCode.BUY_IN_OUT_OF_RANGE, e.errorCode)
    }

    @Test
    fun `정확히 20BB 인 바이인은 허용된다`() {
        val table = newTable()
        val seat = table.sitDown(userId = UserId(1L), buyIn = Chips.of(4_000))
        assertEquals(Chips.of(4_000), seat.stack)
    }

    @Test
    fun `도메인은 바이인 상한을 검사하지 않는다 — 재착석 의무가 상한을 넘을 수 있어 application 이 본다`() {
        val table = newTable()
        val bigBuyIn = Chips.of(200_000_000)
        val seat = table.sitDown(userId = UserId(1L), buyIn = bigBuyIn)
        assertEquals(bigBuyIn, seat.stack)
    }

    @Test
    fun `바이인 범위는 의무가 없으면 20BB 에서 100BB 다`() {
        val range = newTable().buyInRange(Chips.ZERO)
        assertEquals(Chips.of(4_000), range.start)
        assertEquals(Chips.of(20_000), range.endInclusive)
    }

    @Test
    fun `재착석 의무가 20BB 와 100BB 사이면 하한만 올라간다`() {
        val range = newTable().buyInRange(Chips.of(12_000))
        assertEquals(Chips.of(12_000), range.start)
        assertEquals(Chips.of(20_000), range.endInclusive)
    }

    @Test
    fun `재착석 의무가 100BB 를 넘으면 상한도 그 금액까지 올라간다`() {
        val range = newTable().buyInRange(Chips.of(30_000))
        assertEquals(Chips.of(30_000), range.start)
        assertEquals(Chips.of(30_000), range.endInclusive)
    }

    @Test
    fun `칩 추가는 스택에 더하고 정확히 100BB 까지 허용한다`() {
        val table = newTable()
        table.sitDown(userId = UserId(1L), buyIn = Chips.of(10_000))

        table.addChips(1, Chips.of(10_000))

        assertEquals(Chips.of(20_000), table.seatAt(1)!!.stack)
    }

    @Test
    fun `칩 추가로 스택이 100BB 를 넘으면 CHIP_ADD_OUT_OF_RANGE 이고 스택은 그대로다`() {
        val table = newTable()
        table.sitDown(userId = UserId(1L), buyIn = Chips.of(10_000))

        val e = assertFailsWith<ChipAddOutOfRangeException> { table.addChips(1, Chips.of(10_100)) }

        assertEquals(HoldemErrorCode.CHIP_ADD_OUT_OF_RANGE, e.errorCode)
        assertEquals(Chips.of(10_000), table.seatAt(1)!!.stack)
    }

    @Test
    fun `이미 100BB 를 넘긴 스택에는 칩을 추가할 수 없다`() {
        val table = newTable()
        table.sitDown(userId = UserId(1L), buyIn = Chips.of(30_000))

        val e = assertFailsWith<ChipAddOutOfRangeException> { table.addChips(1, Chips.of(100)) }

        assertEquals(HoldemErrorCode.CHIP_ADD_OUT_OF_RANGE, e.errorCode)
    }

    @Test
    fun `0 칩 추가는 CHIP_ADD_OUT_OF_RANGE 로 거부된다`() {
        val table = newTable()
        table.sitDown(userId = UserId(1L), buyIn = Chips.of(10_000))

        assertFailsWith<ChipAddOutOfRangeException> { table.addChips(1, Chips.ZERO) }
    }

    @Test
    fun `앉은 적 없는 사용자의 standUp 은 NOT_SEATED 로 거부된다`() {
        val table = newTable()
        val e = assertFailsWith<NotSeatedException> { table.standUp(UserId(1L)) }
        assertEquals(HoldemErrorCode.NOT_SEATED, e.errorCode)
    }

    @Test
    fun `markPresence 는 좌석의 연결 상태를 바꾼다`() {
        val table = newTable()
        table.sitDown(userId = UserId(1L), buyIn = Chips.of(10_000))

        table.markPresence(UserId(1L), SeatPresence.DISCONNECTED)

        assertEquals(SeatPresence.DISCONNECTED, table.seatAt(1)?.presence)
    }

    @Test
    fun `앉은 적 없는 사용자의 markPresence 는 NOT_SEATED 로 거부된다`() {
        val table = newTable()
        val e = assertFailsWith<NotSeatedException> { table.markPresence(UserId(1L), SeatPresence.DISCONNECTED) }
        assertEquals(HoldemErrorCode.NOT_SEATED, e.errorCode)
    }

    @Test
    fun `공백 테이블 이름은 TABLE_NAME_INVALID 로 거부된다`() {
        val e = assertFailsWith<InvalidTableNameException> { HoldemTable.create("   ", TestTablePolicy) }
        assertEquals(HoldemErrorCode.TABLE_NAME_INVALID, e.errorCode)
    }

    @Test
    fun `30 코드포인트를 넘는 테이블 이름은 TABLE_NAME_INVALID 로 거부된다`() {
        val longName = "😀".repeat(31)
        val e = assertFailsWith<InvalidTableNameException> { HoldemTable.create(longName, TestTablePolicy) }
        assertEquals(HoldemErrorCode.TABLE_NAME_INVALID, e.errorCode)
    }

    @Test
    fun `applyStacks 은 점유되지 않은 좌석 번호가 들어오면 예외를 던진다`() {
        val table = newTable()
        table.sitDown(userId = UserId(1L), buyIn = Chips.of(10_000))

        assertFailsWith<IllegalStateException> {
            table.applyStacks(mapOf(1 to Chips.of(5_000), 7 to Chips.of(1_000)))
        }
    }

    @Test
    fun `applyStacks 은 일부 점유 좌석만 포함된 맵으로 정상 동작하고 나머지 좌석은 변경되지 않는다`() {
        val table = newTable()
        table.sitDown(userId = UserId(1L), buyIn = Chips.of(10_000))
        table.sitDown(userId = UserId(2L), buyIn = Chips.of(8_000))

        table.applyStacks(mapOf(1 to Chips.of(5_000)))

        assertEquals(Chips.of(5_000), table.seatAt(1)?.stack)
        assertEquals(Chips.of(8_000), table.seatAt(2)?.stack)
    }

    @Test
    fun `reconstitute 는 검증하지 않는다`() {
        val table = HoldemTable.reconstitute(
            id = TableId(1L),
            name = "   ",
            policy = TestTablePolicy,
            buttonSeatNo = 42,
            seats = emptyMap(),
        )
        assertEquals("   ", table.name)
        assertEquals(42, table.buttonSeatNo)
    }

    @Test
    fun `advanceBlinds 는 첫 핸드에서 참가 좌석 중 가장 작은 번호를 BB 로 둔다`() {
        val table = newTable()

        val positions = table.advanceBlinds(setOf(1, 2, 3))

        assertEquals(1, positions.bigBlindSeatNo)
        assertEquals(3, positions.smallBlindSeatNo)
        assertEquals(2, positions.buttonSeatNo)
        assertEquals(2, table.buttonSeatNo)
        assertEquals(3, table.smallBlindSeatNo)
        assertEquals(1, table.bigBlindSeatNo)
    }

    @Test
    fun `advanceBlinds 를 좌석 수만큼 연속 호출하면 모든 좌석이 정확히 한 번씩 BB 와 SB 가 된다`() {
        val table = newTable()
        val seatNos = setOf(1, 2, 3, 4)
        val bbSeen = mutableListOf<Int>()
        val sbSeen = mutableListOf<Int>()

        repeat(seatNos.size) {
            val positions = table.advanceBlinds(seatNos)
            bbSeen += positions.bigBlindSeatNo
            sbSeen += positions.smallBlindSeatNo!!
        }

        assertEquals(seatNos, bbSeen.toSet())
        assertEquals(seatNos.size, bbSeen.size)
        assertEquals(seatNos, sbSeen.toSet())
        assertEquals(seatNos.size, sbSeen.size)
    }

    @Test
    fun `한 좌석이 빠져도 남은 좌석들은 BB 를 건너뛰지 않는다`() {
        val table = newTable()
        repeat(4) { table.advanceBlinds(setOf(1, 2, 3, 4)) } // 4핸드를 돌려 좌석2가 빠지기 전 상태를 만든다

        val remaining = setOf(1, 3, 4)
        val bbSeen = mutableListOf<Int>()
        repeat(remaining.size) {
            bbSeen += table.advanceBlinds(remaining).bigBlindSeatNo
        }

        assertEquals(remaining, bbSeen.toSet())
        assertEquals(remaining.size, bbSeen.size)
    }

    @Test
    fun `직전 SB 좌석이 비면 버튼이 그 좌석 번호에 그대로 남는 dead button 이다`() {
        val table = HoldemTable.reconstitute(
            id = TableId(1L),
            name = "t",
            policy = TestTablePolicy,
            buttonSeatNo = 2,
            seats = emptyMap(),
            smallBlindSeatNo = 3, // 직전 SB 였던 좌석 — 이번 핸드엔 없다
            bigBlindSeatNo = 4,   // 직전 BB — 이번 핸드에도 있다
        )

        val positions = table.advanceBlinds(setOf(1, 2, 4))

        assertEquals(3, positions.buttonSeatNo)     // 비어 있어도 그대로
        assertEquals(4, positions.smallBlindSeatNo) // 직전 BB(4) 가 이번엔 참가하므로 dead 아님
        assertEquals(1, positions.bigBlindSeatNo)   // 직전 BB(4) 다음의 참가 좌석, 순환
    }

    @Test
    fun `직전 SB 좌석이 새 BB 로 돌아와도 버튼이 BB 와 겹치지 않는다`() {
        val table = HoldemTable.reconstitute(
            id = TableId(1L),
            name = "t",
            policy = TestTablePolicy,
            buttonSeatNo = 7,
            seats = emptyMap(),
            smallBlindSeatNo = 1,
            bigBlindSeatNo = 4, // 직전 BB(4)·버튼(7)은 나가고 2·3 이 새로 앉았다
        )

        val positions = table.advanceBlinds(setOf(1, 2, 3))

        assertEquals(1, positions.bigBlindSeatNo)
        assertEquals(3, positions.buttonSeatNo)        // 새 SB(4) 바로 앞 참가 좌석
        assertEquals(null, positions.smallBlindSeatNo) // 직전 BB(4) 가 없어 dead small blind
    }

    @Test
    fun `직전 BB 만 남고 새 좌석이 끼어들어도 버튼이 BB 와 겹치지 않는다`() {
        val table = HoldemTable.reconstitute(
            id = TableId(1L),
            name = "t",
            policy = TestTablePolicy,
            buttonSeatNo = 7,
            seats = emptyMap(),
            smallBlindSeatNo = 1,
            bigBlindSeatNo = 4, // 버튼(7)이 나가고 2 가 새로 앉았다
        )

        val positions = table.advanceBlinds(setOf(1, 2, 4))

        assertEquals(1, positions.bigBlindSeatNo)
        assertEquals(2, positions.buttonSeatNo)
        assertEquals(4, positions.smallBlindSeatNo)
    }

    @Test
    fun `직전 BB 좌석이 비면 아무도 SB 를 내지 않는 dead small blind 다`() {
        val table = HoldemTable.reconstitute(
            id = TableId(1L),
            name = "t",
            policy = TestTablePolicy,
            buttonSeatNo = 2,
            seats = emptyMap(),
            smallBlindSeatNo = 3,
            bigBlindSeatNo = 4, // 직전 BB — 이번 핸드엔 없다
        )

        val positions = table.advanceBlinds(setOf(1, 2, 3))

        assertEquals(3, positions.buttonSeatNo)        // 직전 SB(3) 그대로, 이번엔 참가하니 dead 아님
        assertEquals(null, positions.smallBlindSeatNo) // 직전 BB(4) 가 이번엔 없어 dead small blind
        assertEquals(1, positions.bigBlindSeatNo)      // 직전 BB(4) 다음의 참가 좌석, 순환
    }

    @Test
    fun `헤즈업은 매 핸드 버튼이 두 좌석을 번갈아 맡는다`() {
        val table = newTable()

        val first = table.advanceBlinds(setOf(1, 2))
        assertEquals(1, first.buttonSeatNo)
        assertEquals(1, first.smallBlindSeatNo)
        assertEquals(2, first.bigBlindSeatNo)

        val second = table.advanceBlinds(setOf(1, 2))
        assertEquals(2, second.buttonSeatNo)
        assertEquals(2, second.smallBlindSeatNo)
        assertEquals(1, second.bigBlindSeatNo)

        val third = table.advanceBlinds(setOf(1, 2))
        assertEquals(1, third.buttonSeatNo)
    }

    @Test
    fun `3인에서 헤즈업으로 줄어도 직전 BB 는 연속으로 BB 를 내지 않는다`() {
        val table = newTable()
        repeat(3) { table.advanceBlinds(setOf(1, 2, 3)) } // BB: 1 -> 2 -> 3

        val positions = table.advanceBlinds(setOf(2, 3))

        assertEquals(2, positions.bigBlindSeatNo)
        assertEquals(3, positions.buttonSeatNo)
        assertEquals(3, positions.smallBlindSeatNo)
    }

    @Test
    fun `헤즈업 뒤 세 번째 좌석이 들어오면 버튼·SB·BB 가 서로 다른 좌석이다`() {
        val table = newTable()
        table.advanceBlinds(setOf(1, 3))

        val positions = table.advanceBlinds(setOf(1, 2, 3))

        assertEquals(2, positions.buttonSeatNo)
        assertEquals(3, positions.smallBlindSeatNo)
        assertEquals(1, positions.bigBlindSeatNo)
    }

    @Test
    fun `헤즈업 뒤 직전 BB 다음 자리로 들어온 좌석은 바로 BB 가 된다`() {
        val table = newTable()
        table.advanceBlinds(setOf(1, 3))

        val positions = table.advanceBlinds(setOf(1, 3, 4))

        assertEquals(4, positions.bigBlindSeatNo)
        assertEquals(3, positions.smallBlindSeatNo)
        assertEquals(1, positions.buttonSeatNo)
    }

    @Test
    fun `forParticipants 는 2명으로 좁혀지면 BB 가 아닌 좌석이 버튼과 SB 를 겸한다`() {
        val positions = HoldemTable.HandPositions(buttonSeatNo = 2, smallBlindSeatNo = 3, bigBlindSeatNo = 1)

        val actual = positions.forParticipants(setOf(1, 3))

        assertEquals(3, actual.buttonSeatNo)
        assertEquals(3, actual.smallBlindSeatNo)
        assertEquals(1, actual.bigBlindSeatNo)
    }

    @Test
    fun `forParticipants 는 3명 이상이면 명목 버튼을 유지하되 참가하지 않는 SB 는 null 로 좁힌다`() {
        val positions = HoldemTable.HandPositions(buttonSeatNo = 2, smallBlindSeatNo = 3, bigBlindSeatNo = 1)

        val actual = positions.forParticipants(setOf(1, 2, 4))

        assertEquals(2, actual.buttonSeatNo)
        assertEquals(null, actual.smallBlindSeatNo) // 3은 참가자가 아니다
        assertEquals(1, actual.bigBlindSeatNo)
    }

    @Test
    fun `forParticipants 는 참가하지 않는 버튼을 dead button 으로 그대로 둔다`() {
        val positions = HoldemTable.HandPositions(buttonSeatNo = 2, smallBlindSeatNo = 3, bigBlindSeatNo = 1)

        val actual = positions.forParticipants(setOf(1, 3, 4))

        assertEquals(2, actual.buttonSeatNo) // 2는 참가자가 아니다 — dead button
        assertEquals(3, actual.smallBlindSeatNo)
        assertEquals(1, actual.bigBlindSeatNo)
    }

    @Test
    fun `candidateSeatNos 는 스택이 양수인 점유 좌석만 포함한다`() {
        val table = newTable()
        table.sitDown(userId = UserId(1L), buyIn = Chips.of(10_000))
        table.sitDown(userId = UserId(2L), buyIn = Chips.of(10_000))
        table.applyStacks(mapOf(2 to Chips.ZERO))

        assertEquals(setOf(1), table.candidateSeatNos())
    }

    @Test
    fun `아무도 앉지 않은 테이블은 close 하면 closedAt 이 기록된다`() {
        val table = newTable()
        val at = Instant.parse("2026-01-01T00:00:00Z")

        table.close(at)

        assertEquals(at, table.closedAt)
    }

    @Test
    fun `앉은 사용자가 있으면 close 는 TABLE_NOT_EMPTY 이고 닫히지 않는다`() {
        val table = newTable()
        table.sitDown(userId = UserId(1L), buyIn = Chips.of(10_000))

        val e = assertFailsWith<TableNotEmptyException> { table.close(Instant.now()) }

        assertEquals(HoldemErrorCode.TABLE_NOT_EMPTY, e.errorCode)
        assertNull(table.closedAt)
    }

    @Test
    fun `정책이 다른 두 테이블은 바이인 범위와 블라인드를 각자 적용한다`() {
        val small = HoldemTable.create("small", TestTablePolicy)
        val big = HoldemTable.create("big", TablePolicy.of(500, 1_000, 10, 50))

        assertEquals(Chips.of(200), small.bigBlind)
        assertEquals(Chips.of(1_000), big.bigBlind)
        assertEquals(Chips.of(4_000)..Chips.of(20_000), small.buyInRange(Chips.ZERO))
        assertEquals(Chips.of(10_000)..Chips.of(50_000), big.buyInRange(Chips.ZERO))
        // 한쪽 하한(4,000)은 다른 쪽에서는 미달이다.
        small.sitDown(UserId(1L), Chips.of(4_000))
        assertFailsWith<BuyInOutOfRangeException> { big.sitDown(UserId(1L), Chips.of(4_000)) }
        assertEquals(Chips.of(50_000), big.maxStack())
    }
}
