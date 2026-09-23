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
    smallBlindSeatNo: Int?,
    bigBlindSeatNo: Int?,
    seats: Map<Int, Seat>,
    val version: Long,
) {
    var buttonSeatNo: Int? = buttonSeatNo
        private set

    /**
     * 직전 핸드에서 정해진 스몰 블라인드 좌석 번호(명목상 — 그 핸드에서 실제로 포스팅했는지와 무관하다).
     * 다음 핸드의 버튼 좌석을 정하는 데만 쓰인다. 핸드가 한 번도 시작된 적이 없으면 null.
     */
    var smallBlindSeatNo: Int? = smallBlindSeatNo
        private set

    /**
     * 직전 핸드에서 정해진 빅 블라인드 좌석 번호. 다음 핸드의 스몰·빅 블라인드를 정하는 데 쓰인다.
     * 핸드가 한 번도 시작된 적이 없으면 null.
     */
    var bigBlindSeatNo: Int? = bigBlindSeatNo
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

    /** 다음 핸드에서 실제로 쓸 포지션. [smallBlindSeatNo] 가 `null` 이면 아무도 스몰 블라인드를 내지 않는다(dead small blind). */
    data class HandPositions(val buttonSeatNo: Int, val smallBlindSeatNo: Int?, val bigBlindSeatNo: Int)

    /**
     * 참가 좌석을 받아 다음 핸드의 버튼·SB·BB 를 정하고 테이블 상태를 갱신한다.
     *
     * 불변식: 빅 블라인드 좌석은 매 핸드 좌석 번호 순으로 정확히 한 칸씩 전진한다. 나머지는 거기서
     * 유도된다 — newSB = 직전 BB 좌석(이번 핸드에 참가하지 않으면 dead small blind),
     * newButton = 직전 SB 좌석(비어 있어도 그대로 둔다 → dead button). 직전 BB 가 없으면(테이블의
     * 첫 핸드) 참가 좌석 중 가장 작은 번호를 BB 로 둔다.
     *
     * 헤즈업(참가 2명)은 이 불변식을 덮어쓴다 — 버튼이 SB 를 겸하고, 매 핸드 두 좌석이 번갈아 버튼을 맡는다.
     * 헤즈업 분기는 이 메서드 안에만 둔다 — [Hand.start] 는 여기서 정해진 값을 그대로 받아 쓴다.
     */
    fun advanceBlinds(participatingSeatNos: Set<Int>): HandPositions {
        val sorted = participatingSeatNos.sorted()
        check(sorted.size >= 2) { "블라인드를 정하려면 참가 좌석이 2명 이상이어야 한다: $sorted" }

        val (nominalButton, nominalSmallBlind, nominalBigBlind) = if (sorted.size == 2) {
            headsUpPositions(sorted)
        } else {
            fullRingPositions(sorted)
        }

        buttonSeatNo = nominalButton
        smallBlindSeatNo = nominalSmallBlind
        bigBlindSeatNo = nominalBigBlind

        return HandPositions(
            buttonSeatNo = nominalButton,
            smallBlindSeatNo = nominalSmallBlind.takeIf { it in sorted },
            bigBlindSeatNo = nominalBigBlind,
        )
    }

    /** 헤즈업: 버튼이 SB 를 겸한다. 직전 버튼이 이번 핸드에도 참가하면 반대 좌석으로 넘어가고, 아니면 작은 좌석 번호부터 다시 시작한다. */
    private fun headsUpPositions(sorted: List<Int>): Triple<Int, Int, Int> {
        val prevButton = buttonSeatNo
        val newButton = if (prevButton != null && prevButton in sorted) {
            sorted.first { it != prevButton }
        } else {
            sorted.min()
        }
        val newBigBlind = sorted.first { it != newButton }
        return Triple(newButton, newButton, newBigBlind)
    }

    /**
     * 3인 이상. 직전 BB 가 없으면(테이블의 첫 핸드) 참가 좌석 중 가장 작은 번호를 BB 로 두고,
     * 그 앞의 두 좌석(사이클 상 마지막, 마지막에서 두 번째)을 각각 SB·버튼으로 삼는다.
     */
    private fun fullRingPositions(sorted: List<Int>): Triple<Int, Int, Int> {
        val prevBigBlind = bigBlindSeatNo
            ?: return Triple(sorted[sorted.size - 2], sorted.last(), sorted.first())

        val prevSmallBlind = smallBlindSeatNo ?: sorted.last()
        val newBigBlind = sorted.firstOrNull { it > prevBigBlind } ?: sorted.first()
        return Triple(prevSmallBlind, prevBigBlind, newBigBlind)
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
                smallBlindSeatNo = null,
                bigBlindSeatNo = null,
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
            smallBlindSeatNo: Int? = null,
            bigBlindSeatNo: Int? = null,
        ): HoldemTable = HoldemTable(id, name, smallBlind, bigBlind, buttonSeatNo, smallBlindSeatNo, bigBlindSeatNo, seats, version)
    }
}
