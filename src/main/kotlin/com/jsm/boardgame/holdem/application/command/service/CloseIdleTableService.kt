package com.jsm.boardgame.holdem.application.command.service

import com.jsm.boardgame.holdem.application.command.usecase.CloseIdleTableCommand
import com.jsm.boardgame.holdem.application.command.usecase.CloseIdleTableUseCase
import com.jsm.boardgame.holdem.application.event.TableClosed
import com.jsm.boardgame.holdem.application.port.HandStore
import com.jsm.boardgame.holdem.application.port.JoinQueue
import com.jsm.boardgame.holdem.domain.model.TableId
import com.jsm.boardgame.holdem.domain.repository.HoldemTableRepository
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant

/**
 * 호출자가 [com.jsm.boardgame.holdem.application.port.TableExecutor] 위에서 부른다. 스윕이 "한가하다" 고 본
 * 뒤 실행기에 오기까지 사이에 누가 앉았을 수 있어 세 조건(좌석·핸드·대기열)을 여기서 다시 확인한다.
 */
@Service
@Transactional
class CloseIdleTableService(
    private val tables: HoldemTableRepository,
    private val handStore: HandStore,
    private val joinQueue: JoinQueue,
    private val eventPublisher: ApplicationEventPublisher,
    private val clock: Clock,
) : CloseIdleTableUseCase {

    override fun close(command: CloseIdleTableCommand): Boolean {
        val tableId = TableId(command.tableId)
        val table = tables.findById(tableId) ?: return false
        if (table.occupiedSeats().isNotEmpty() || handStore.findInProgress(tableId) != null || joinQueue.entriesOf(tableId).isNotEmpty()) {
            return false
        }
        table.close(Instant.now(clock))
        tables.save(table)
        eventPublisher.publishEvent(TableClosed(tableId))
        return true
    }
}
