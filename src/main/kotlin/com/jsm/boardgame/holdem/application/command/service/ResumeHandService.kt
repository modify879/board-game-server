package com.jsm.boardgame.holdem.application.command.service

import com.jsm.boardgame.holdem.application.command.usecase.ResumeHandCommand
import com.jsm.boardgame.holdem.application.command.usecase.ResumeHandUseCase
import com.jsm.boardgame.holdem.application.event.HandBroadcastRequested
import com.jsm.boardgame.holdem.application.port.HandStore
import com.jsm.boardgame.holdem.domain.model.TableId
import com.jsm.boardgame.holdem.domain.repository.HoldemTableRepository
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 재시작 복구에서 좌석 전원이 재접속했을 때 진행 중 핸드를 재개한다. 호출자는 사용자가 아니라
 * `HandRecovery` 뿐이다.
 *
 * 상태는 바꾸지 않는다 - [HandBroadcastRequested] 를 다시 발행해 현재 핸드 상태를 좌석별로 다시
 * 내보내는 것으로 끝난다. `TurnTimer` 가 이미 이 이벤트를 듣고 있어, 그 리스너가 차례 타이머를
 * 처음부터 다시 건다(복구 후 턴 타이머는 남은 시간을 이어받지 않고 새로 시작해야 한다는 규칙이
 * 여기서 그대로 지켜진다) - 그래서 여기서 타이머를 따로 재기동하지 않는다.
 *
 * 진행 중 핸드가 없으면(이미 정산됐거나 이미 취소됐으면) 조용히 끝난다 - 복구 절차는 멱등해야
 * 한다.
 */
@Service
@Transactional
class ResumeHandService(
    private val tables: HoldemTableRepository,
    private val handStore: HandStore,
    private val eventPublisher: ApplicationEventPublisher,
) : ResumeHandUseCase {

    override fun resume(command: ResumeHandCommand) {
        val tableId = TableId(command.tableId)
        val hand = handStore.find(tableId) ?: return
        val table = tables.findById(tableId) ?: return

        log.info("전원 재접속을 확인해 핸드를 재개합니다: tableId={}", command.tableId)
        eventPublisher.publishEvent(HandBroadcastRequested(tableId, table, hand))
    }

    companion object {
        private val log = LoggerFactory.getLogger(ResumeHandService::class.java)
    }
}
