package com.jsm.boardgame.holdem.application.command.service

import com.jsm.boardgame.holdem.application.event.HandBroadcastRequested
import com.jsm.boardgame.holdem.application.port.HandStore
import com.jsm.boardgame.holdem.domain.model.Hand
import com.jsm.boardgame.holdem.domain.model.HoldemTable
import com.jsm.boardgame.holdem.domain.model.TableId
import com.jsm.boardgame.holdem.domain.repository.HoldemTableRepository
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component

/**
 * 핸드 종료 정산. StartHandService·PlayActionService 가 공유한다 — 로직을 복제하지 않는다.
 * 참가 좌석의 스택만 테이블에 반영하고 지갑은 건드리지 않는다. 칩은 테이블에 남고
 * 기립할 때만 지갑으로 돌아간다.
 *
 * 참가 좌석은 핸드가 소유한다 — 핸드 도중 새로 앉은 좌석은 다음 핸드부터 참가한다.
 */
@Component
class HandSettler(
    private val tables: HoldemTableRepository,
    private val handStore: HandStore,
    private val eventPublisher: ApplicationEventPublisher,
) {
    fun settle(tableId: TableId, table: HoldemTable, hand: Hand) {
        val stacks = hand.seatNos.associateWith { seatNo -> hand.stackOf(seatNo) }
        table.applyStacks(stacks)
        tables.save(table)
        handStore.remove(tableId)
        // 정산 후에도 hand 를 null 로 넘기지 않는다 — 클라이언트가 쇼다운 결과(showdownRanks/payouts)를
        // 봐야 한다. HandStore 에서는 이미 지웠을 뿐이다.
        eventPublisher.publishEvent(HandBroadcastRequested(tableId, table, hand))
    }
}
