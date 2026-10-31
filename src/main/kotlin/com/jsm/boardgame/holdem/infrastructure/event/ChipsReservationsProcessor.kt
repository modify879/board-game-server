package com.jsm.boardgame.holdem.infrastructure.event

import com.jsm.boardgame.holdem.application.command.usecase.ApplyChipsReservationsCommand
import com.jsm.boardgame.holdem.application.command.usecase.ApplyChipsReservationsUseCase
import com.jsm.boardgame.holdem.application.event.ChipsReservationsDue
import com.jsm.boardgame.holdem.application.port.TableExecutor
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionalEventListener

/**
 * [ChipsReservationsDue] 를 커밋 후에 받아 칩 추가 예약 적용을 테이블 실행기에 올린다. [JoinRequestsProcessor] 와 같이
 * `post` 로 넘긴다(커미터 스레드를 붙잡지 않는다). 같은 정산이 [ChipsReservationsDue] 를 `JoinRequestsDue` 보다 먼저
 * 발행하므로 실행기에서도 예약 적용이 대기열 처리·빈 테이블 닫기보다 먼저 돈다.
 */
@Component
class ChipsReservationsProcessor(
    private val applyChipsReservationsUseCase: ApplyChipsReservationsUseCase,
    private val tableExecutor: TableExecutor,
) {
    @TransactionalEventListener
    fun onChipsReservationsDue(event: ChipsReservationsDue) {
        // AFTER_COMMIT 리스너는 예외를 던지지 않는다(transactions.md).
        try {
            tableExecutor.post(event.tableId) {
                applyChipsReservationsUseCase.apply(ApplyChipsReservationsCommand(event.tableId.value))
            }
        } catch (e: Exception) {
            log.error("칩 추가 예약 적용을 올리지 못했습니다: tableId={}", event.tableId.value, e)
        }
    }

    private companion object {
        val log = LoggerFactory.getLogger(ChipsReservationsProcessor::class.java)
    }
}
