package com.jsm.boardgame.holdem.application.command.service

import com.jsm.boardgame.holdem.application.event.HandBroadcastRequested
import com.jsm.boardgame.holdem.application.event.JoinRequestsDue
import com.jsm.boardgame.holdem.application.port.HandStore
import com.jsm.boardgame.holdem.application.port.NextHandCountdown
import com.jsm.boardgame.holdem.domain.model.Hand
import com.jsm.boardgame.holdem.domain.model.HoldemTable
import com.jsm.boardgame.holdem.domain.model.SeatStatus
import com.jsm.boardgame.holdem.domain.model.TableId
import com.jsm.boardgame.holdem.domain.repository.HoldemTableRepository
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component

/**
 * 핸드 종료 정산. StartScheduledHandService·PlayActionService·ExpireTurnService 가 공유한다 —
 * 로직을 복제하지 않는다. 참가 좌석의 스택만 테이블에 반영한다. 칩은 테이블에 남고 기립할 때만
 * 지갑으로 돌아간다.
 *
 * 참가 좌석은 핸드가 소유한다 — 핸드 도중 새로 앉은 좌석은 다음 핸드부터 참가한다.
 *
 * hand.seatNos 중 이미 점유가 풀린 좌석은 ExpireTurnService 가 1분 무응답 폴드로 즉시 기립시킨
 * 좌석뿐이다 — 그 좌석은 이 핸드가 끝날 때까지 스택이 더 바뀌지 않으므로 정산에서 건너뛴다.
 * 폴드가 아닌데 점유가 풀려 있으면 불변식 위반이다(HoldemTable.applyStacks 의 점유 가드는
 * 그대로 둔다 — 여기서는 넘길 대상 자체를 걸러낸다).
 *
 * 스택 반영 직후, 스택이 0이 된 참가 좌석은 자동으로 기립시킨다(0칩 자동 기립) — 9자리가 칩
 * 없는 사람에게 묶이지 않게 한다. 핸드가 이미 끝난 뒤라 going south(핸드 도중 칩을 빼는 것) 제약과
 * 충돌하지 않는다. 돌려줄 칩이 없으므로 지갑 이체는 부르지 않는다(StandUpService 가 스택 0일 때
 * 이체를 건너뛰는 것과 같은 이유). 이 클래스는 WalletTransfer 를 전혀 부르지 않는다 — 핸드 도중 남은
 * 참가 요청의 바이인 이체는 커밋 후 별도 트랜잭션(JoinRequestsProcessor)에서 처리한다.
 *
 * 정산 뒤 후보(스택이 있는 점유 좌석)가 2명 이상이면 다음 핸드 카운트다운을 새로 건다 — 핸드가
 * 도는 동안은 카운트다운이 없었으므로 항상 처음부터 시작하는 셈이다. `NextHandCountdown` 이 상대
 * 시간(단조 시계)만 쓴다.
 */
@Component
class HandSettler(
    private val tables: HoldemTableRepository,
    private val handStore: HandStore,
    private val eventPublisher: ApplicationEventPublisher,
    private val countdown: NextHandCountdown,
) {
    fun settle(tableId: TableId, table: HoldemTable, hand: Hand) {
        val stacks = hand.seatNos.associateWith { seatNo -> hand.stackOf(seatNo) }
        val toApply = stacks.filterKeys { seatNo ->
            val occupied = table.seatAt(seatNo) != null
            if (!occupied && hand.statusOf(seatNo) != SeatStatus.FOLDED) {
                error("점유되지 않은 좌석은 폴드 상태여야 한다: seatNo=$seatNo, status=${hand.statusOf(seatNo)}")
            }
            occupied
        }
        table.applyStacks(toApply)

        for ((seatNo, stack) in toApply) {
            if (!stack.isZero()) continue
            val userId = table.seatAt(seatNo)?.userId ?: continue
            table.standUp(userId)
            log.info("auto stand-up after 0-stack settle: tableId={}, seatNo={}, userId={}", tableId.value, seatNo, userId)
        }

        handStore.remove(tableId)

        if (table.candidateSeatNos().size >= 2) {
            countdown.restart(tableId)
        }

        tables.save(table)
        // 정산 후에도 hand 를 null 로 넘기지 않는다 — 클라이언트가 쇼다운 결과(showdownRanks/payouts)를
        // 봐야 한다. HandStore 에서는 이미 지웠을 뿐이다.
        eventPublisher.publishEvent(HandBroadcastRequested(tableId, table, hand))
        // 대기열 처리는 여기서 하지 않는다 — 지갑 이체가 이 트랜잭션(정산)과 같은 트랜잭션에서 실패하면
        // 예외를 잡아도 트랜잭션이 rollback-only 가 되어 정산 전체가 롤백된다. 커밋 후 별도 트랜잭션으로
        // 넘긴다(JoinRequestsProcessor → ProcessJoinRequestsService → AdmitJoinRequestService, 항목마다 하나씩).
        // 대기열이 비어 있어도 트리거는 그냥 버려지므로(peekHead 가 null) 조건 없이 발행한다.
        eventPublisher.publishEvent(JoinRequestsDue(tableId))
    }

    companion object {
        private val log = LoggerFactory.getLogger(HandSettler::class.java)
    }
}
