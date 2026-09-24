package com.jsm.boardgame.holdem.infrastructure.timer

import com.jsm.boardgame.holdem.application.command.usecase.ProcessJoinRequestsCommand
import com.jsm.boardgame.holdem.application.command.usecase.ProcessJoinRequestsUseCase
import com.jsm.boardgame.holdem.application.event.JoinRequestsDue
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionalEventListener

/**
 * [JoinRequestsDue] 를 커밋 후에 받아 참가 요청 처리를 건다. NextHandTimer/TurnTimer 와 달리
 * 스케줄러가 필요 없다 — 정산 트랜잭션이 이미 끝난 뒤라 지금 바로 처리해도 된다(직접 호출).
 */
@Component
class JoinRequestsProcessor(
    private val processJoinRequestsUseCase: ProcessJoinRequestsUseCase,
) {
    @TransactionalEventListener
    fun onJoinRequestsDue(event: JoinRequestsDue) {
        processJoinRequestsUseCase.process(ProcessJoinRequestsCommand(event.tableId.value))
    }
}
