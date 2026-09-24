package com.jsm.boardgame.holdem.presentation.ws.payload

import com.jsm.boardgame.holdem.domain.model.Hand
import com.jsm.boardgame.holdem.domain.model.HandResult
import com.jsm.boardgame.holdem.domain.model.HoldemTable
import com.jsm.boardgame.holdem.domain.model.TableId

/**
 * TablePublicView/SeatPrivateView 조립기. HandBroadcaster(브로드캐스트)와
 * HoldemSubscriptionSnapshotListener(재접속 1회 스냅샷)가 같이 쓴다 — 그래서 top-level
 * 함수로 뺐다(같은 패키지라도 private 로는 다른 파일에서 못 쓴다).
 *
 * 스프링 타입을 하나도 받지 않는다 — 그래서 스프링 없이 단위 테스트할 수 있다.
 */
fun publicViewOf(tableId: TableId, table: HoldemTable, hand: Hand?): TablePublicView {
    val seats = table.occupiedSeats().map { seat ->
        if (hand != null && seat.seatNo in hand.seatNos) {
            SeatPublicView(
                seatNo = seat.seatNo,
                userId = seat.userId,
                stack = hand.stackOf(seat.seatNo).amount,
                totalContributed = hand.totalContributedBy(seat.seatNo).amount,
                status = hand.statusOf(seat.seatNo).name,
                presence = seat.presence.name,
            )
        } else {
            SeatPublicView(
                seatNo = seat.seatNo,
                userId = seat.userId,
                stack = seat.stack.amount,
                totalContributed = 0L,
                status = "SITTING_OUT",
                presence = seat.presence.name,
            )
        }
    }
    return TablePublicView(
        tableId = tableId.value,
        handInProgress = hand != null && !hand.isFinished,
        street = hand?.street?.name,
        board = hand?.board?.map { it.toString() } ?: emptyList(),
        pot = hand?.potTotal()?.amount ?: 0L,
        toActSeatNo = hand?.toActSeatNo,
        buttonSeatNo = table.buttonSeatNo,
        seats = seats,
        result = hand?.result?.let(::handResultPublicViewOf),
    )
}

private fun handResultPublicViewOf(result: HandResult): HandResultPublicView {
    val payouts = result.payouts
        .filterValues { it.isPositive() }
        .toSortedMap()
        .map { (seatNo, amount) ->
            val shownCategory = if (seatNo in result.showdownWinners) result.showdownRanks.getValue(seatNo).category.name else null
            PayoutPublicView(seatNo, amount.amount, shownCategory)
        }
    return HandResultPublicView(payouts)
}

/** [seatNo] 자신의 홀카드와 지금 할 수 있는 행동만 담는다. 다른 좌석의 카드는 이 함수의 인자로도 들어오지 않는다. */
fun privateViewOf(tableId: TableId, seatNo: Int, hand: Hand): SeatPrivateView =
    SeatPrivateView(
        tableId = tableId.value,
        seatNo = seatNo,
        holeCards = hand.holeCardsOf(seatNo).map { it.toString() },
        availableActions = hand.availableActionsFor(seatNo)?.let { actions ->
            AvailableActionsView(
                canCheck = actions.canCheck,
                callAmount = actions.callAmount?.amount,
                minRaiseTo = actions.minRaiseTo?.amount,
                maxRaiseTo = actions.maxRaiseTo?.amount,
            )
        },
    )
