package com.jsm.boardgame.holdem.application.command.service

import com.jsm.boardgame.holdem.application.port.RecentDepartures
import com.jsm.boardgame.holdem.application.port.WalletTransfer
import com.jsm.boardgame.holdem.domain.model.HoldemTable
import com.jsm.boardgame.holdem.domain.model.TableId
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * "이 핸드가 끝나면 일어난다" 예약의 실행. 핸드가 끝나는 두 곳(HandSettler, CancelHandService)이
 * 카운트다운 판단·대기열 트리거보다 먼저 부른다 — 그래야 비운 좌석을 대기열이 채우고 빈 테이블이 닫힌다.
 * 기립 처리는 StandUpService 와 같다(재착석 의무 기록, 양수 스택은 지갑으로). 호출자의 트랜잭션 안에서 돈다.
 */
@Component
class LeaveReservations(
    private val recentDepartures: RecentDepartures,
    private val walletTransfer: WalletTransfer,
) {
    fun standUpAll(tableId: TableId, table: HoldemTable) {
        for (seat in table.occupiedSeats().filter { it.leaveReserved }) {
            val returned = table.standUp(seat.userId)
            recentDepartures.record(seat.userId, returned)
            if (returned.isPositive()) {
                walletTransfer.fromGame(seat.userId, returned.amount, tableId, memo = "holdem")
            }
            log.info(
                "기립했습니다: userId={}, tableId={}, returnedChips={}, reason=LEAVE_RESERVED",
                seat.userId.value, tableId.value, returned.amount,
            )
        }
    }

    private companion object {
        private val log = LoggerFactory.getLogger(LeaveReservations::class.java)
    }
}
