package com.jsm.boardgame.holdem.application.command.service

import com.jsm.boardgame.holdem.application.event.HandBroadcastRequested
import com.jsm.boardgame.holdem.application.port.HandStore
import com.jsm.boardgame.holdem.domain.model.Hand
import com.jsm.boardgame.holdem.domain.model.HoldemTable
import com.jsm.boardgame.holdem.domain.model.TableId
import com.jsm.boardgame.holdem.domain.repository.HoldemTableRepository
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component

/**
 * 핸드 종료 정산. StartHandService·PlayActionService 가 공유한다 — 로직을 복제하지 않는다.
 * 참가 좌석의 스택만 테이블에 반영하고 지갑은 건드리지 않는다. 칩은 테이블에 남고
 * 기립할 때만 지갑으로 돌아간다.
 *
 * 참가 좌석은 핸드가 소유한다 — 핸드 도중 새로 앉은 좌석은 다음 핸드부터 참가한다.
 *
 * 스택 반영 직후, 스택이 0이 된 참가 좌석은 자동으로 기립시킨다(0칩 자동 기립) — 9자리가 칩
 * 없는 사람에게 묶이지 않게 한다. 핸드가 이미 끝난 뒤라 going south(핸드 도중 칩을 빼는 것) 제약과
 * 충돌하지 않는다. 돌려줄 칩이 없으므로 지갑 이체는 부르지 않는다 — 이 클래스는 애초에
 * WalletTransfer 를 주입받지 않는다(StandUpService 가 스택 0일 때 이체를 건너뛰는 것과 같은 이유).
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

        for ((seatNo, stack) in stacks) {
            if (!stack.isZero()) continue
            val userId = table.seatAt(seatNo)?.userId ?: continue
            table.standUp(userId)
            log.info("auto stand-up after 0-stack settle: tableId={}, seatNo={}, userId={}", tableId.value, seatNo, userId)
        }

        tables.save(table)
        handStore.remove(tableId)
        // 정산 후에도 hand 를 null 로 넘기지 않는다 — 클라이언트가 쇼다운 결과(showdownRanks/payouts)를
        // 봐야 한다. HandStore 에서는 이미 지웠을 뿐이다.
        eventPublisher.publishEvent(HandBroadcastRequested(tableId, table, hand))
    }

    companion object {
        private val log = LoggerFactory.getLogger(HandSettler::class.java)
    }
}
