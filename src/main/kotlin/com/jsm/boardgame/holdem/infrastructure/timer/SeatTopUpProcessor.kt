package com.jsm.boardgame.holdem.infrastructure.timer

import com.jsm.boardgame.holdem.application.command.usecase.TopUpTableSeatsCommand
import com.jsm.boardgame.holdem.application.command.usecase.TopUpTableSeatsUseCase
import com.jsm.boardgame.holdem.application.event.SeatTopUpDue
import com.jsm.boardgame.holdem.application.port.TableExecutor
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionalEventListener

/**
 * [SeatTopUpDue] 를 커밋 후에 받아 테이블 스레드에서 좌석 합산을 건다. [JoinRequestsProcessor] 와 같은
 * 이유로 `post` 다 — 커미터 스레드를 붙잡지 않는다. 실패는 `post` 가 로그로 남기고, 정산은 이미 커밋됐다.
 */
@Component
class SeatTopUpProcessor(
    private val topUpTableSeats: TopUpTableSeatsUseCase,
    private val tableExecutor: TableExecutor,
) {
    @TransactionalEventListener
    fun onSeatTopUpDue(event: SeatTopUpDue) {
        tableExecutor.post(event.tableId) { topUpTableSeats.topUp(TopUpTableSeatsCommand(event.tableId.value)) }
    }
}
