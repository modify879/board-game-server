package com.jsm.boardgame.holdem.application.command.service

import com.jsm.boardgame.holdem.application.command.usecase.TopUpTableSeatsCommand
import com.jsm.boardgame.holdem.application.command.usecase.TopUpTableSeatsUseCase
import com.jsm.boardgame.holdem.application.event.HandBroadcastRequested
import com.jsm.boardgame.holdem.domain.model.TableId
import com.jsm.boardgame.holdem.domain.repository.HoldemTableRepository
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionOperations

/**
 * 테이블 전 좌석을 합치고, 하나라도 늘었으면 공개 뷰를 한 번 브로드캐스트한다. 이 클래스는
 * @Transactional 이 아니다 — 좌석별 트랜잭션은 [SeatTopUper] 가 갖고, 브로드캐스트만 커밋 후에
 * 나가도록 짧은 트랜잭션을 연다(HandBroadcaster 가 커밋 동기화에 매달린다). 늘어난 스택은 정산
 * 브로드캐스트 뒤의 후속 갱신으로 클라이언트에 닿는다.
 */
@Service
class TopUpTableSeatsService(
    private val tables: HoldemTableRepository,
    private val tableSeatsTopUp: TableSeatsTopUp,
    private val eventPublisher: ApplicationEventPublisher,
    private val transactions: TransactionOperations,
) : TopUpTableSeatsUseCase {

    override fun topUp(command: TopUpTableSeatsCommand) {
        val tableId = TableId(command.tableId)
        if (!tableSeatsTopUp.topUpAll(tableId)) return
        transactions.executeWithoutResult {
            val table = tables.findById(tableId) ?: return@executeWithoutResult
            eventPublisher.publishEvent(HandBroadcastRequested(tableId, table, null))
        }
    }
}
