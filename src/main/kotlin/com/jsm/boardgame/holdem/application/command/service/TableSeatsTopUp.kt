package com.jsm.boardgame.holdem.application.command.service

import com.jsm.boardgame.holdem.application.port.WalletBalance
import com.jsm.boardgame.holdem.domain.model.TableId
import com.jsm.boardgame.holdem.domain.repository.HoldemTableRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * 테이블의 점유 좌석마다 지갑 잔액을 스택에 합친다. 합산은 편의 기능이라 정산·핸드 시작을 절대
 * 실패시키거나 롤백시키면 안 된다 — 그래서 호출자의 트랜잭션 밖에서(테이블 스레드 위에서) 부르고,
 * 좌석마다 [SeatTopUper] 의 REQUIRES_NEW 로 격리하며, 실패는 ERROR 로 남기고 넘어간다. 못 합친 좌석은
 * 다음 핸드 시작이나 다음 입금 때 합쳐진다.
 *
 * 잔액을 락 없이 먼저 읽어 0 이면 지갑 행 락을 잡지 않는다 — 대부분의 좌석은 입금이 없다.
 * 읽고 이체하는 사이에 잔액이 바뀌어도 이체가 락 안에서 다시 읽으므로 괜찮다. 하나라도 합쳤으면 true.
 */
@Component
class TableSeatsTopUp(
    private val tables: HoldemTableRepository,
    private val walletBalance: WalletBalance,
    private val seatTopUper: SeatTopUper,
) {
    fun topUpAll(tableId: TableId): Boolean {
        val table = tables.findById(tableId) ?: return false
        var changed = false
        for (seat in table.occupiedSeats()) {
            try {
                if (walletBalance.balanceOf(seat.userId) <= 0) continue
                if (seatTopUper.topUp(tableId, seat.userId)) changed = true
            } catch (e: Exception) {
                log.error("지갑 합산에 실패해 이 좌석은 건너뜁니다(다음 핸드 시작·입금 때 합쳐집니다): tableId={}, userId={}", tableId.value, seat.userId, e)
            }
        }
        return changed
    }

    companion object {
        private val log = LoggerFactory.getLogger(TableSeatsTopUp::class.java)
    }
}
