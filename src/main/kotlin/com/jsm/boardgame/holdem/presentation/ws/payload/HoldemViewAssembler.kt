package com.jsm.boardgame.holdem.presentation.ws.payload

import com.jsm.boardgame.holdem.application.port.LastHandResult
import com.jsm.boardgame.holdem.domain.model.Hand
import com.jsm.boardgame.holdem.domain.model.HoldemTable
import com.jsm.boardgame.holdem.domain.model.LeaveReservation
import com.jsm.boardgame.holdem.domain.model.TableId
import com.jsm.boardgame.holdem.domain.model.isInProgress
import java.time.Duration

/**
 * TablePublicView/SeatPrivateView 조립기. HandBroadcaster(브로드캐스트)와
 * HoldemSubscriptionSnapshotListener(재접속 1회 스냅샷)가 같이 쓴다 — 그래서 top-level
 * 함수로 뺐다(같은 패키지라도 private 로는 다른 파일에서 못 쓴다).
 *
 * 스프링 타입을 하나도 받지 않는다 — 그래서 스프링 없이 단위 테스트할 수 있다. [nextHandRemaining]·
 * [turnRemaining] 은 NextHandCountdown·TurnCountdown 포트에서 호출자가 미리 읽어와 넘긴다(단조 시계 기준 남은 시간).
 */
fun publicViewOf(
    tableId: TableId,
    table: HoldemTable,
    hand: Hand?,
    lastResult: LastHandResult?,
    seq: Long,
    nextHandRemaining: Duration?,
    turnRemaining: Duration?,
    turnTimeout: Duration?,
): TablePublicView {
    // 결과를 보일지는 여기서만 정한다 — 핸드 없음(재구독·취소 뒤) 또는 방금 끝난 핸드일 때만이다.
    // 호출자는 그 경우의 [lastResult] 를 채워 넘긴다(끝난 핸드는 HandSettler 가 만든 값).
    val finished = if (hand == null || hand.isFinished) lastResult else null
    val turnRemainingMs = if (hand.isInProgress && hand?.toActSeatNo != null) turnRemaining?.toMillis() else null
    val seats = table.occupiedSeats().map { seat ->
        // 끝난 핸드의 배지는 사용자까지 맞아야 보인다 — 결과 표시 중 기립·재착석으로 같은 좌석의 주인이 바뀔 수 있다.
        val lastAction = finished?.lastActionOf(seat.seatNo, seat.userId)
            ?: if (hand != null && seat.seatNo in hand.seatNos) hand.lastActionOf(seat.seatNo)?.name else null
        if (hand != null && seat.seatNo in hand.seatNos) {
            SeatPublicView(
                seatNo = seat.seatNo,
                userId = seat.userId.value,
                // 끝난 핸드는 좌석 값을 쓴다 — 다음 핸드 전까지 칩 추가로 좌석 스택이 바뀔 수 있어 좌석 값이 최신이다.
                stack = if (hand.isFinished) seat.stack.amount else hand.stackOf(seat.seatNo).amount,
                totalContributed = hand.totalContributedBy(seat.seatNo).amount,
                status = hand.statusOf(seat.seatNo).name,
                lastAction = lastAction,
                presence = seat.presence.name,
                leaveReserved = seat.leaveReserved,
            )
        } else {
            SeatPublicView(
                seatNo = seat.seatNo,
                userId = seat.userId.value,
                stack = seat.stack.amount,
                totalContributed = 0L,
                status = "SITTING_OUT",
                lastAction = lastAction,
                presence = seat.presence.name,
                leaveReserved = seat.leaveReserved,
            )
        }
    }
    return TablePublicView(
        tableId = tableId.value,
        tableName = table.name,
        handInProgress = hand.isInProgress,
        street = hand?.street?.name,
        board = hand?.board?.map { it.toString() } ?: lastResult?.board ?: emptyList(),
        pot = hand?.potTotal()?.amount ?: 0L,
        pots = hand?.collectedPots()?.map { PotPublicView(it.amount.amount, it.eligibleSeats.sorted()) } ?: emptyList(),
        toActSeatNo = hand?.toActSeatNo,
        // 테이블의 버튼은 명목상 값이다 — 헤즈업으로 줄면 핸드의 실제 버튼이 달라져 SB·BB 와 어긋난다.
        buttonSeatNo = hand?.buttonSeatNo ?: table.buttonSeatNo,
        smallBlindSeatNo = hand?.smallBlindSeatNo,
        bigBlindSeatNo = hand?.bigBlindSeatNo,
        seats = seats,
        result = finished?.let(::handResultPublicViewOf),
        revealedHands = hand?.revealedSeatNos?.sorted()
            ?.map { RevealedHandView(it, hand.holeCardsOf(it).map { c -> c.toString() }) } ?: emptyList(),
        nextHandInMs = nextHandRemaining?.toMillis(),
        turnRemainingMs = turnRemainingMs,
        turnTimeoutMs = if (turnRemainingMs != null) turnTimeout?.toMillis() else null,
        seq = seq,
    )
}

private fun handResultPublicViewOf(result: LastHandResult): HandResultPublicView =
    HandResultPublicView(
        result.payouts.map { PayoutPublicView(it.seatNo, it.userId.value, it.amount) },
        result.shownHands.map { ShownHandView(it.seatNo, it.userId.value, it.holeCards, it.category) },
    )

/** [seatNo] 자신의 홀카드와 지금 할 수 있는 행동만 담는다. 다른 좌석의 카드는 이 함수의 인자로도 들어오지 않는다. */
fun privateViewOf(tableId: TableId, seatNo: Int, hand: Hand, seq: Long, leaveReservation: LeaveReservation?): SeatPrivateView =
    SeatPrivateView(
        tableId = tableId.value,
        seatNo = seatNo,
        holeCards = hand.holeCardsOf(seatNo).map { it.toString() },
        currentHand = hand.currentRankOf(seatNo)?.let { CurrentHandView(it.category.name, it.tiebreakers) },
        availableActions = hand.availableActionsFor(seatNo)?.let { actions ->
            AvailableActionsView(
                canCheck = actions.canCheck,
                callAmount = actions.callAmount?.amount,
                minRaiseTo = actions.minRaiseTo?.amount,
                maxRaiseTo = actions.maxRaiseTo?.amount,
            )
        },
        leaveReservation = leaveReservation?.name,
        seq = seq,
    )
