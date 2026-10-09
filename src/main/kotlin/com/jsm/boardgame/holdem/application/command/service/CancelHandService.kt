package com.jsm.boardgame.holdem.application.command.service

import com.jsm.boardgame.holdem.application.command.usecase.CancelHandCommand
import com.jsm.boardgame.holdem.application.command.usecase.CancelHandUseCase
import com.jsm.boardgame.holdem.application.event.HandBroadcastRequested
import com.jsm.boardgame.holdem.application.event.JoinRequestsDue
import com.jsm.boardgame.holdem.application.port.HandStore
import com.jsm.boardgame.holdem.application.port.NextHandCountdown
import com.jsm.boardgame.holdem.application.port.WalletTransfer
import com.jsm.boardgame.holdem.domain.model.Hand
import com.jsm.boardgame.holdem.domain.model.TableId
import com.jsm.boardgame.holdem.domain.repository.HoldemTableRepository
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 재시작 복구가 전원 재접속에 실패했을 때 진행 중 핸드를 무른다. 호출자는 사용자가 아니라
 * `HandRecovery`(전원 재접속 실패)와 `RunoutTimer`(런아웃 걸음이 반복 실패)뿐이다. 후자는 서버 버그라
 * 사용자가 일으킬 수 없다 — 재접속 여부로 환불을 유발할 수 있는 경로를 `HandRecovery` 가 일부러
 * 피하는 것과 다르다.
 *
 * 되돌리는 값은 `HandSnapshot.startingStacks` 다 - 핸드 도중 오간 칩은 애초에 `HoldemTable` 의
 * 좌석 스택에 반영되지 않는다(정산은 항상 [HandSettler.settle] 을 거쳐야 좌석에 반영된다). 그래서
 * 이 복원은 대개 이미 있는 값을 다시 쓰는 것이지만, `hand.stackOf()` 는 핸드 중 값이라 쓰지 않는다.
 *
 * 진행 중 핸드가 없으면(이미 정산됐거나 이미 취소됐으면) 조용히 끝난다 - 복구 절차는 죽었다
 * 다시 떠도 같은 행을 다시 탈 수 있어야 해서 멱등해야 한다.
 *
 * 핸드 도중 폴드한 채 기립한 좌석([Hand.departedUserIds])은 테이블에 좌석이 없으므로 시작 스택이 아니라
 * 그 핸드에 넣은 투입액을 지갑으로 돌려준다 - 기립할 때 이미 받은 남은 스택과 합해 시작 스택이 된다
 * (사용자 결정: 무르기는 모두에게 되돌리는 것이다). 테이블 행을 먼저 잠근 채 지갑을 부르므로 잠금 순서가 맞다.
 *
 * 취소 뒤에는 `HandSettler` 와 같이 다음 핸드 카운트다운을 걸고 참가 요청 처리를 트리거한다 - 안 그러면
 * 테이블이 멈춘다.
 *
 * 좌석은 그대로 둔다 - 취소는 핸드를 무르는 것이지 사람을 내보내는 게 아니다. 안 돌아온 사람은
 * 이 서비스가 끝난 뒤 평소의 연결 타이머가 새로 돌면서 퇴장 여부를 가른다.
 */
@Service
@Transactional
class CancelHandService(
    private val tables: HoldemTableRepository,
    private val handStore: HandStore,
    private val walletTransfer: WalletTransfer,
    private val countdown: NextHandCountdown,
    private val eventPublisher: ApplicationEventPublisher,
) : CancelHandUseCase {

    override fun cancel(command: CancelHandCommand) {
        val tableId = TableId(command.tableId)
        val hand = handStore.find(tableId) ?: return
        val table = tables.findById(tableId) ?: return

        val departed = hand.departedUserIds
        val refunds = hand.snapshot().startingStacks.filterKeys { it !in departed }
        table.applyStacks(refunds)
        tables.save(table)
        handStore.remove(tableId)

        val departedRefunds = departed.mapValues { (seatNo, _) -> hand.totalContributedBy(seatNo) }
        for ((seatNo, userId) in departed) {
            val contributed = departedRefunds.getValue(seatNo)
            if (contributed.isPositive()) {
                walletTransfer.fromGame(userId, contributed.amount, tableId, memo = "holdem")
            }
        }

        log.info(
            "복구 실패로 핸드를 취소하고 환불했습니다: tableId={}, refunds={}, departedRefunds={}",
            command.tableId, refunds, departedRefunds,
        )
        if (table.candidateSeatNos().size >= 2) {
            countdown.restart(tableId)
        }
        eventPublisher.publishEvent(HandBroadcastRequested(tableId, table, null))
        eventPublisher.publishEvent(JoinRequestsDue(tableId))
    }

    companion object {
        private val log = LoggerFactory.getLogger(CancelHandService::class.java)
    }
}
