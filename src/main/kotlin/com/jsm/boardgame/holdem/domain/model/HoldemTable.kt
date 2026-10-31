package com.jsm.boardgame.holdem.domain.model

import com.jsm.boardgame.holdem.domain.exception.AlreadySeatedException
import com.jsm.boardgame.holdem.domain.exception.BuyInOutOfRangeException
import com.jsm.boardgame.holdem.domain.exception.ChipAddOutOfRangeException
import com.jsm.boardgame.holdem.domain.exception.InvalidTableNameException
import com.jsm.boardgame.holdem.domain.exception.NotSeatedException
import com.jsm.boardgame.holdem.domain.exception.TableNotEmptyException
import com.jsm.boardgame.holdem.domain.exception.TableFullException
import java.time.Instant

@JvmInline value class TableId(val value: Long) {
    override fun toString(): String = value.toString()
}

class HoldemTable private constructor(
    val id: TableId?,
    val name: String,
    val policy: TablePolicy,
    buttonSeatNo: Int?,
    smallBlindSeatNo: Int?,
    bigBlindSeatNo: Int?,
    seats: Map<Int, Seat>,
    closedAt: Instant?,
) {
    val smallBlind: Chips get() = policy.smallBlind
    val bigBlind: Chips get() = policy.bigBlind

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

    /** 방치돼 닫힌 시각. null 이면 열려 있다. 닫힌 테이블은 삭제하지 않고 남긴다 — 원장이 테이블 id 를 참조한다. */
    var closedAt: Instant? = closedAt
        private set

    private val seats: MutableMap<Int, Seat> = seats.toMutableMap()

    /** 아무도 앉아 있지 않아야 닫을 수 있다. 핸드·대기열 여부는 도메인이 모르니 호출 위치(CloseIdleTableService)가 확인한다. */
    fun close(at: Instant) {
        if (seats.isNotEmpty()) {
            throw TableNotEmptyException("앉은 사용자가 있는 테이블은 닫을 수 없습니다: tableId=$id")
        }
        closedAt = at
    }

    /** 가장 낮은 번호의 빈 좌석에 강제로 앉힌다 — 좌석을 고르지 않는다(FIFO 대기열, ProcessJoinRequestsService). */
    fun sitDown(userId: UserId, buyIn: Chips, postBlindImmediately: Boolean = false): Seat {
        if (seatOf(userId) != null) {
            throw AlreadySeatedException("이미 이 테이블에 앉아 있는 사용자입니다: userId=$userId")
        }
        // 상한은 application 이 본다 — 재착석 의무가 테이블 정책의 최대 바이인(기본 100BB)을 넘을 수 있어 도메인은 하한만 안다.
        if (buyIn < policy.minBuyIn) {
            throw BuyInOutOfRangeException("바이인은 ${policy.minBuyInBigBlinds}BB(${policy.minBuyIn}) 이상이어야 합니다: $buyIn")
        }
        val seatNo = (1..MAX_SEATS).firstOrNull { it !in seats }
            ?: throw TableFullException("빈 좌석이 없습니다: tableId=$id")
        val seat = Seat.of(seatNo, userId, buyIn, postBlindImmediately)
        seats[seatNo] = seat
        return seat
    }

    /**
     * 바이인으로 고를 수 있는 범위. 재착석 의무([obligation])가 하한을 올리고, 상한을 넘으면 상한도 같이 올린다 —
     * 의무가 상한보다 우선한다(하우스 룰 7).
     */
    fun buyInRange(obligation: Chips): ClosedRange<Chips> = policy.buyInRange(obligation)

    /** 칩 추가로 채울 수 있는 스택 상한(정책의 최대 바이인 BB). 바이인 상한과 같다. */
    fun maxStack(): Chips = policy.maxStack

    /**
     * 좌석 스택에 [amount] 를 더한다(칩 추가). 결과가 [maxStack] 을 넘으면 거부한다 — 플레이 중 스택이 최대 바이인(기본 100BB)을 넘는 건
     * 허용이지만 추가로 넘기는 건 아니다. 핸드 진행 중에는 부르면 안 된다 — 도메인은 모르니 호출 위치(AddChipsService)가 보장한다.
     */
    fun addChips(seatNo: Int, amount: Chips) {
        val seat = seats[seatNo] ?: error("점유되지 않은 좌석에 칩을 추가할 수 없다: seatNo=$seatNo, occupied=${seats.keys.sorted()}")
        if (!amount.isPositive() || seat.stack + amount > maxStack()) {
            throw ChipAddOutOfRangeException("칩 추가는 0 초과이고 스택이 ${maxStack()} 을 넘을 수 없습니다: stack=${seat.stack}, amount=$amount")
        }
        seat.applyStack(seat.stack + amount)
    }

    fun hasEmptySeat(): Boolean = seats.size < MAX_SEATS

    fun standUp(userId: UserId): Chips {
        val seat = seatOf(userId) ?: throw NotSeatedException("이 테이블에 앉아 있지 않은 사용자입니다: userId=$userId")
        seats.remove(seat.seatNo)
        return seat.stack
    }

    /** 핸드가 끝나면 일어나겠다는 예약. 이미 예약돼 있으면 종류만 바꾼다. */
    fun reserveLeave(userId: UserId, reservation: LeaveReservation) {
        val seat = seatOf(userId) ?: throw NotSeatedException("이 테이블에 앉아 있지 않은 사용자입니다: userId=$userId")
        seat.applyLeaveReservation(reservation)
    }

    /** 예약을 푼다. 예약이 없었으면 아무 일도 없다. */
    fun cancelLeaveReservation(userId: UserId) {
        val seat = seatOf(userId) ?: throw NotSeatedException("이 테이블에 앉아 있지 않은 사용자입니다: userId=$userId")
        seat.applyLeaveReservation(null)
    }

    /** 핸드가 끝나면 지갑에서 [amount] 만큼 가져오겠다는 예약. 이미 있으면 덮어쓴다. 한도·잔액은 적용 때 본다. */
    fun reserveChips(userId: UserId, amount: Chips) {
        val seat = seatOf(userId) ?: throw NotSeatedException("이 테이블에 앉아 있지 않은 사용자입니다: userId=$userId")
        if (!amount.isPositive()) {
            throw ChipAddOutOfRangeException("칩 추가 예약은 0 초과여야 합니다: amount=$amount")
        }
        seat.applyChipsReservation(amount)
    }

    /** 칩 추가 예약을 푼다. 예약이 없었으면 아무 일도 없다. */
    fun cancelChipsReservation(userId: UserId) {
        val seat = seatOf(userId) ?: throw NotSeatedException("이 테이블에 앉아 있지 않은 사용자입니다: userId=$userId")
        seat.applyChipsReservation(null)
    }

    /**
     * 칩 추가 예약을 적용한다 — min(예약, [maxStack] − 스택, [walletBalance]) 를 [CHIP_UNIT] 으로 내림해 스택에 더하고,
     * 예약은 항상 지운다. 더한 금액을 돌려주며(0 이면 아무것도 안 더했다) 지갑 이체는 호출자가 한다.
     * 핸드 진행 중에는 부르면 안 된다 — 호출 위치(ApplyChipsReservationsService)가 보장한다.
     */
    fun applyChipsReservation(userId: UserId, walletBalance: Chips): Chips {
        val seat = seatOf(userId) ?: throw NotSeatedException("이 테이블에 앉아 있지 않은 사용자입니다: userId=$userId")
        val reserved = seat.chipsReservation ?: return Chips.ZERO
        seat.applyChipsReservation(null)
        // 플레이 중 스택은 한도를 넘을 수 있다 — 그때 남은 자리는 0 이다.
        val room = if (seat.stack >= maxStack()) Chips.ZERO else maxStack() - seat.stack
        val amount = Chips.floorOf(minOf(reserved, room, walletBalance).amount)
        if (amount.isZero()) return Chips.ZERO
        seat.applyStack(seat.stack + amount)
        return amount
    }

    fun markPresence(userId: UserId, presence: SeatPresence) {
        val seat = seatOf(userId) ?: throw NotSeatedException("이 테이블에 앉아 있지 않은 사용자입니다: userId=$userId")
        seat.applyPresence(presence)
    }

    fun seatOf(userId: UserId): Seat? = seats.values.find { it.userId == userId }

    fun seatAt(seatNo: Int): Seat? = seats[seatNo]

    fun occupiedSeats(): List<Seat> = seats.values.sortedBy { it.seatNo }

    /** 후보 = 점유 좌석 중 스택이 양수인 것. [seatForNextHand] 와 카운트다운 리셋 판단이 이 정의를 공유한다. */
    fun candidateSeatNos(): Set<Int> = occupiedSeats().filter { it.stack.isPositive() }.map { it.seatNo }.toSet()

    /**
     * 이번 핸드에 딜인할 좌석. [positions] 는 실제 참가자로 좁힌 포지션, [extraPostSeatNos] 는 블라인드와 별도로
     * 진입료(BB 한 번)를 내는 좌석이다.
     */
    data class HandSeating(val positions: HandPositions, val participantSeatNos: Set<Int>, val extraPostSeatNos: Set<Int>)

    /**
     * [advanceBlinds] 가 정한 명목 [positions] 로 이번 핸드의 참가자를 정하고 좌석의 BB 대기·즉시 포스팅 플래그를 갱신한다.
     *
     * 참가자 = 후보 − (아직 대기 중인데 이번 BB 가 아닌 좌석) − (버튼~첫 블라인드 구간에 걸린 즉시 포스팅 신규 좌석).
     * 대기 좌석이 SB·버튼 자리에 걸리면 그 자리는 비는 셈이라 dead small blind/dead button 이 그대로 적용된다.
     * Robert's Rules of Poker, Button and Blind Use: 새 참가자는 포스팅을 자청해도 버튼~SB 구간에서는 딜인되지 않는다.
     *
     * 대기·차단 규칙은 판을 굶겨 죽이지 않는다: 제외 뒤 2명 미만이면 판이 못 서고, 판이 못 서면 건너뛸 블라인드 회전
     * 자체가 없으므로 대기·차단시킬 이유가 없다 — 후보 전원의 대기 플래그를 풀고 전원을 참가시키며, 이번 핸드만큼은
     * 진입료도 면제한다(테이블이 사실상 다시 시작하는 셈이라 BB 대기 면제와 같은 이유다). 테이블의 진짜 첫 핸드(전원 대기
     * 기본값), 비었다가 다시 찬 테이블, 정규 참가자 파산까지 이 규칙 하나로 덮인다.
     *
     * 블라인드 포스팅 자체가 이번 핸드의 진입료다 — 빚진 좌석이 실제 SB/BB 로 좁혀지면 추가 포스팅을 따로 받지 않는다.
     * 대기 좌석은 owing 이 될 수 없으므로(즉시 참가를 고른 좌석만 owing), 빚진 좌석이 실제 SB 가 되는 경로는 헤즈업
     * 좁히기(다른 좌석은 전부 대기)뿐이다 — 그때는 SB 포스팅을 진입료 대신으로 받아들이는 의도된 단순화다.
     * 빚은 실제로 참가한 좌석만 소비한다 — 차단된 좌석은 빚을 그대로 들고 기다린다.
     */
    fun seatForNextHand(positions: HandPositions): HandSeating {
        val candidateSeatNos = candidateSeatNos()
        seats[positions.bigBlindSeatNo]?.clearAwaitingBigBlind()

        val awaitingSeatNos = occupiedSeats().filter { it.awaitingBigBlind }.map { it.seatNo }.toSet()
        val owingSeatNos = occupiedSeats()
            .filter { it.seatNo in candidateSeatNos && it.owesImmediatePost }
            .map { it.seatNo }
            .toSet()
        val blockedNewPlayers = owingSeatNos.filter { isBetweenButtonAndFirstBlind(it, positions) }.toSet()

        var participatingSeatNos = candidateSeatNos - awaitingSeatNos - blockedNewPlayers
        val relief = participatingSeatNos.size < 2
        if (relief) {
            candidateSeatNos.forEach { seats[it]?.clearAwaitingBigBlind() }
            participatingSeatNos = candidateSeatNos
        }

        val actual = positions.forParticipants(participatingSeatNos)
        val extraPostSeatNos = if (relief) {
            emptySet()
        } else {
            owingSeatNos.intersect(participatingSeatNos) - setOfNotNull(actual.smallBlindSeatNo, actual.bigBlindSeatNo)
        }

        owingSeatNos.filter { it in participatingSeatNos }.forEach { seats[it]?.consumeImmediatePost() }
        return HandSeating(actual, participatingSeatNos, extraPostSeatNos)
    }

    /**
     * 버튼(포함)부터 버튼 뒤 첫 블라인드까지의 원형 좌석 구간 안인가. 첫 블라인드는 SB, dead SB 면 BB 다 —
     * SB 는 구간에 포함하고 BB 는 제외한다(SB~BB 사이 좌석은 막지 않는다). dead button 으로 BB 가 빈 좌석을
     * 건너뛰면 버튼~SB 사이에 신규 좌석이 앉을 수 있어 정확히 버튼·SB 자리만 보면 놓친다.
     */
    private fun isBetweenButtonAndFirstBlind(seatNo: Int, positions: HandPositions): Boolean {
        fun distanceFromButton(to: Int) = Math.floorMod(to - positions.buttonSeatNo, MAX_SEATS)
        val sb = positions.smallBlindSeatNo
        return if (sb != null) {
            distanceFromButton(seatNo) <= distanceFromButton(sb)
        } else {
            distanceFromButton(seatNo) < distanceFromButton(positions.bigBlindSeatNo)
        }
    }

    /** 다음 핸드에서 실제로 쓸 포지션. [smallBlindSeatNo] 가 `null` 이면 아무도 스몰 블라인드를 내지 않는다(dead small blind). */
    data class HandPositions(val buttonSeatNo: Int, val smallBlindSeatNo: Int?, val bigBlindSeatNo: Int) {
        /** 실제 참가자 기준으로 좁힌 이번 핸드의 포지션. 테이블에 저장되는 회전값(명목 포지션)은 바꾸지 않는다. */
        fun forParticipants(participants: Set<Int>): HandPositions {
            check(bigBlindSeatNo in participants) {
                "빅 블라인드 좌석은 항상 참가자여야 한다: bigBlindSeatNo=$bigBlindSeatNo, participants=$participants"
            }
            if (participants.size == 2) {
                // 헤즈업으로 좁혀지면 BB 가 아닌 나머지 한 좌석이 버튼·SB 를 겸한다(dead button 없음).
                val other = participants.first { it != bigBlindSeatNo }
                return HandPositions(other, other, bigBlindSeatNo)
            }
            return HandPositions(buttonSeatNo, smallBlindSeatNo?.takeIf { it in participants }, bigBlindSeatNo)
        }
    }

    /**
     * 참가 좌석을 받아 다음 핸드의 버튼·SB·BB 를 정하고 테이블 상태를 갱신한다. 여기서 정하는 값은
     * "명목" 포지션이다 — 대기 중인 좌석도 후보에 포함해 회전이 밀리지 않게 하고, 실제 참가자 기준
     * 조정은 [HandPositions.forParticipants] 가 별도로 한다.
     *
     * 불변식: 빅 블라인드 좌석은 매 핸드 좌석 번호 순으로 정확히 한 칸씩 전진한다(Robert's Rules,
     * dead button: "The big blind is posted by the player due for it"). 나머지는 거기서 유도된다 —
     * newSB = 직전 BB 좌석(이번 핸드에 참가하지 않으면 dead small blind), newButton = 직전 SB 좌석
     * (비어 있어도 그대로 둔다 → dead button). 단, 직전 핸드가 헤즈업이었으면(버튼==SB) 새 버튼은
     * 새 SB(직전 BB) 바로 앞 참가 좌석으로 정한다 — 헤즈업 뒤에는 버튼이 곧 SB 였으므로 "직전 SB"를
     * 그대로 쓰면 새 BB 와 겹칠 수 있어서다. 직전 BB 가 없으면(테이블의 첫 핸드) 참가 좌석 중 가장
     * 작은 번호를 BB 로 둔다.
     *
     * 헤즈업(참가 2명)은 이 불변식을 덮어쓴다 — Robert's Rules, Button and Blind Use: "in heads-up play
     * with two blinds, the small blind is on the button." 버튼이 SB 를 겸하고, BB 는 직전 BB 다음
     * 참가 좌석으로 정해 TDA Rule 34(같은 좌석이 연속으로 BB 를 내지 않는다)를 지킨다. 헤즈업 뒤 3인이 되면
     * 버튼은 "직전 SB" 가 아니라 새 SB 바로 앞 좌석이다 — 헤즈업에서는 버튼이 곧 SB 였다
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

    /**
     * 헤즈업: 버튼이 SB 를 겸한다(Robert's Rules, Button and Blind Use). 직전 BB 가 없으면(첫 핸드)
     * 작은 좌석 번호가 버튼/SB, 나머지가 BB 다. 그 외에는 BB 가 직전 BB 다음 참가 좌석으로 전진하고
     * (TDA Rule 34 — 같은 좌석이 연속으로 BB 를 내지 않는다) 나머지 한 좌석이 버튼/SB 를 겸한다.
     */
    private fun headsUpPositions(sorted: List<Int>): Triple<Int, Int, Int> {
        val prevBigBlind = bigBlindSeatNo
        val newBigBlind = if (prevBigBlind == null) {
            sorted.first { it != sorted.min() }
        } else {
            sorted.firstOrNull { it > prevBigBlind } ?: sorted.first()
        }
        val newButton = sorted.first { it != newBigBlind }
        return Triple(newButton, newButton, newBigBlind)
    }

    /**
     * 3인 이상. 직전 BB 가 없으면(테이블의 첫 핸드) 참가 좌석 중 가장 작은 번호를 BB 로 두고,
     * 그 앞의 두 좌석(사이클 상 마지막, 마지막에서 두 번째)을 각각 SB·버튼으로 삼는다.
     *
     * 직전 핸드가 헤즈업이었으면(버튼==SB, [smallBlindSeatNo] 로 판별) 새 SB 는 직전 BB 고, 새 버튼은
     * "직전 SB" 가 아니라 새 SB 바로 앞(좌석 번호 오름차순 기준 직전) 참가 좌석이다 — 헤즈업에서는
     * 버튼이 곧 SB 였으므로 그 값을 그대로 쓰면 새로 정해진 BB 와 좌석이 겹칠 수 있다.
     *
     * 직전 SB 와 새 BB 가 같은 좌석이면(그 사이 좌석들이 새로 차고 직전 BB 가 나가 BB 가 한 바퀴 돌아 직전 SB 로
     * 온 경우) dead button 을 유지할 수 없어, 헤즈업 뒤와 같은 규칙으로 새 버튼을 새 SB(직전 BB) 바로 앞 참가 좌석으로 정한다.
     */
    private fun fullRingPositions(sorted: List<Int>): Triple<Int, Int, Int> {
        val prevBigBlind = bigBlindSeatNo
            ?: return Triple(sorted[sorted.size - 2], sorted.last(), sorted.first())

        val newBigBlind = sorted.firstOrNull { it > prevBigBlind } ?: sorted.first()

        if (buttonSeatNo != null && buttonSeatNo == smallBlindSeatNo) {
            val newSmallBlind = prevBigBlind
            val newButton = sorted.lastOrNull { it < newSmallBlind } ?: sorted.last()
            return Triple(newButton, newSmallBlind, newBigBlind)
        }

        val prevSmallBlind = smallBlindSeatNo ?: sorted.last()
        if (prevSmallBlind == newBigBlind) {
            val newButton = sorted.lastOrNull { it < prevBigBlind } ?: sorted.last()
            return Triple(newButton, prevBigBlind, newBigBlind)
        }
        return Triple(prevSmallBlind, prevBigBlind, newBigBlind)
    }

    /**
     * [stacks] 에 없는 점유 좌석의 스택은 변경되지 않는다 — 그 핸드에 참가하지 않은 좌석(BB 대기)이 있고,
     * 차례 시간 초과(시간은 설정값) 기립은 한 좌석만 정산한다.
     */
    fun applyStacks(stacks: Map<Int, Chips>) {
        for ((seatNo, stack) in stacks) {
            val seat = seats[seatNo] ?: error("점유되지 않은 좌석에 스택을 쓸 수 없다: seatNo=$seatNo, occupied=${seats.keys.sorted()}")
            seat.applyStack(stack)
        }
    }

    /**
     * [seatNos] 중 스택이 0 인 좌석을 일으켜 세우고(0칩 자동 기립) 기립한 좌석을 돌려준다 — 9자리가 칩 없는 사람에게
     * 묶이지 않게 한다. [applyStacks] 다음에 불러야 한다. 칩 추가 예약이 있는 좌석은 퇴장 예약이 없으면 남겨 둔다 —
     * 예약 적용이 채우거나, 못 채우면 그때 기립시킨다. 퇴장 예약이 함께 있으면 퇴장이 이긴다(예약은 좌석과 함께 사라진다).
     * 돌려줄 칩이 없으므로 지갑 이체·재착석 의무 기록은 호출자 몫이다.
     */
    fun standUpBusted(seatNos: Collection<Int>): List<Seat> = seatNos.mapNotNull { seatNo ->
        val seat = seats[seatNo] ?: return@mapNotNull null
        if (!seat.stack.isZero() || (seat.chipsReservation != null && !seat.leaveReserved)) return@mapNotNull null
        seats.remove(seatNo)
        seat
    }

    companion object {
        const val MAX_SEATS = 9
        private const val MAX_NAME_LENGTH = 30

        fun create(name: String, policy: TablePolicy): HoldemTable {
            val trimmed = name.trim()
            if (trimmed.isEmpty() || trimmed.codePointCount(0, trimmed.length) > MAX_NAME_LENGTH) {
                throw InvalidTableNameException("테이블 이름이 올바르지 않습니다: '$name'")
            }
            return HoldemTable(
                id = null,
                name = trimmed,
                policy = policy,
                buttonSeatNo = null,
                smallBlindSeatNo = null,
                bigBlindSeatNo = null,
                seats = emptyMap(),
                closedAt = null,
            )
        }

        /** 영속 복원 전용 — 검증하지 않는다. */
        fun reconstitute(
            id: TableId,
            name: String,
            policy: TablePolicy,
            buttonSeatNo: Int?,
            seats: Map<Int, Seat>,
            smallBlindSeatNo: Int? = null,
            bigBlindSeatNo: Int? = null,
            closedAt: Instant? = null,
        ): HoldemTable = HoldemTable(id, name, policy, buttonSeatNo, smallBlindSeatNo, bigBlindSeatNo, seats, closedAt)
    }
}
