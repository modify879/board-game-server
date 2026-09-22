package com.jsm.boardgame.holdem.domain.model

import com.jsm.boardgame.holdem.domain.exception.AlreadySeatedException
import com.jsm.boardgame.holdem.domain.exception.BuyInOutOfRangeException
import com.jsm.boardgame.holdem.domain.exception.InvalidTableNameException
import com.jsm.boardgame.holdem.domain.exception.NotSeatedException
import com.jsm.boardgame.holdem.domain.exception.SeatNoOutOfRangeException
import com.jsm.boardgame.holdem.domain.exception.SeatTakenException

@JvmInline value class TableId(val value: Long)

class HoldemTable private constructor(
    val id: TableId?,
    val name: String,
    val smallBlind: Chips,
    val bigBlind: Chips,
    buttonSeatNo: Int?,
    seats: Map<Int, Seat>,
    val version: Long,
) {
    var buttonSeatNo: Int? = buttonSeatNo
        private set

    private val seats: MutableMap<Int, Seat> = seats.toMutableMap()

    fun sitDown(seatNo: Int, userId: Long, buyIn: Chips): Seat {
        if (seatNo !in 1..MAX_SEATS) {
            throw SeatNoOutOfRangeException("좌석 번호는 1..$MAX_SEATS 여야 합니다: $seatNo")
        }
        if (seats.containsKey(seatNo)) {
            throw SeatTakenException("이미 점유된 좌석입니다: seatNo=$seatNo")
        }
        if (seatOf(userId) != null) {
            throw AlreadySeatedException("이미 이 테이블에 앉아 있는 사용자입니다: userId=$userId")
        }
        val minBuyIn = bigBlind * MIN_BUY_IN_BB
        val maxBuyIn = bigBlind * MAX_BUY_IN_BB
        if (buyIn < minBuyIn || buyIn > maxBuyIn) {
            throw BuyInOutOfRangeException("바이인은 $minBuyIn..$maxBuyIn 범위여야 합니다: $buyIn")
        }

        val seat = Seat.of(seatNo, userId, buyIn)
        seats[seatNo] = seat
        return seat
    }

    fun standUp(userId: Long): Chips {
        val seat = seatOf(userId) ?: throw NotSeatedException("이 테이블에 앉아 있지 않은 사용자입니다: userId=$userId")
        seats.remove(seat.seatNo)
        return seat.stack
    }

    fun markPresence(userId: Long, presence: SeatPresence) {
        val seat = seatOf(userId) ?: throw NotSeatedException("이 테이블에 앉아 있지 않은 사용자입니다: userId=$userId")
        seat.applyPresence(presence)
    }

    fun seatOf(userId: Long): Seat? = seats.values.find { it.userId == userId }

    fun seatAt(seatNo: Int): Seat? = seats[seatNo]

    fun occupiedSeats(): List<Seat> = seats.values.sortedBy { it.seatNo }

    fun moveButtonToNextOccupiedSeat() {
        val occupiedSeatNos = seats.keys.sorted()
        if (occupiedSeatNos.isEmpty()) {
            error("점유된 좌석이 없는 테이블에서 버튼을 옮길 수 없다")
        }

        val current = buttonSeatNo
        buttonSeatNo = if (current == null) {
            occupiedSeatNos.first()
        } else {
            occupiedSeatNos.firstOrNull { it > current } ?: occupiedSeatNos.first()
        }
    }

    /**
     * 정산 후 좌석의 스택을 갱신한다.
     *
     * [stacks] 에 없는 점유 좌석의 스택은 변경되지 않는다.
     * 핸드가 진행 중에 새로운 사용자가 착석할 수 있기 때문이다.
     */
    fun applyStacks(stacks: Map<Int, Chips>) {
        for ((seatNo, stack) in stacks) {
            val seat = seats[seatNo] ?: error("점유되지 않은 좌석에 스택을 쓸 수 없다: seatNo=$seatNo, occupied=${seats.keys.sorted()}")
            seat.applyStack(stack)
        }
    }

    companion object {
        const val MAX_SEATS = 9
        private const val MAX_NAME_LENGTH = 30
        private const val MIN_BUY_IN_BB = 40
        private const val MAX_BUY_IN_BB = 100

        // 블라인드는 지금 테이블마다 다르게 할 요구가 없어 고정값이다. 필드로 들고 있는 건
        // 나중에 테이블별로 블라인드를 달리할 자리를 남겨두기 위해서다. 별도 Blinds VO 는 아직 안 만든다.
        val SMALL_BLIND: Chips = Chips.of(100)
        val BIG_BLIND: Chips = Chips.of(200)

        fun create(name: String): HoldemTable {
            val trimmed = name.trim()
            if (trimmed.isEmpty() || trimmed.codePointCount(0, trimmed.length) > MAX_NAME_LENGTH) {
                throw InvalidTableNameException("테이블 이름이 올바르지 않습니다: '$name'")
            }
            return HoldemTable(
                id = null,
                name = trimmed,
                smallBlind = SMALL_BLIND,
                bigBlind = BIG_BLIND,
                buttonSeatNo = null,
                seats = emptyMap(),
                version = 0,
            )
        }

        /** 영속 복원 전용 — 검증하지 않는다. */
        fun reconstitute(
            id: TableId,
            name: String,
            smallBlind: Chips,
            bigBlind: Chips,
            buttonSeatNo: Int?,
            seats: Map<Int, Seat>,
            version: Long,
        ): HoldemTable = HoldemTable(id, name, smallBlind, bigBlind, buttonSeatNo, seats, version)
    }
}
