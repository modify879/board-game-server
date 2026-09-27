package com.jsm.boardgame.holdem.application.command.service

import com.jsm.boardgame.holdem.application.command.usecase.CancelJoinRequestCommand
import com.jsm.boardgame.holdem.application.command.usecase.CancelJoinRequestUseCase
import com.jsm.boardgame.holdem.application.exception.JoinRequestNotFoundException
import com.jsm.boardgame.holdem.application.port.JoinQueue
import com.jsm.boardgame.holdem.application.port.JoinQueueNotifier
import org.springframework.stereotype.Service

/** 대기열은 이제 DB 가 아니라 인메모리(JoinQueue)라 트랜잭션이 필요 없다. command.tableId 는
 *  쓰지 않는다 — 옛 CancelJoinRequestService 도 테이블별로 검증하지 않았다(HoldemTable.cancelJoinRequest
 *  가 userId 로만 찾았다). userId 는 전역에서 한 번에 하나의 대기열에만 있을 수 있어 그걸로 충분하다. */
@Service
class CancelJoinRequestService(
    private val joinQueue: JoinQueue,
    private val notifier: JoinQueueNotifier,
) : CancelJoinRequestUseCase {

    override fun cancel(command: CancelJoinRequestCommand) {
        val tableId = joinQueue.removeByUserId(command.userId)
            ?: throw JoinRequestNotFoundException("취소할 참가 요청이 없습니다: userId=${command.userId}")
        notifier.notifyPositions(tableId, joinQueue.entriesOf(tableId))
    }
}
