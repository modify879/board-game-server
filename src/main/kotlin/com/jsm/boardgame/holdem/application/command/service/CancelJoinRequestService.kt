package com.jsm.boardgame.holdem.application.command.service

import com.jsm.boardgame.holdem.domain.model.UserId
import com.jsm.boardgame.holdem.application.command.usecase.CancelJoinRequestCommand
import com.jsm.boardgame.holdem.application.command.usecase.CancelJoinRequestUseCase
import com.jsm.boardgame.holdem.application.exception.JoinRequestNotFoundException
import com.jsm.boardgame.holdem.application.port.JoinQueue
import com.jsm.boardgame.holdem.application.port.JoinQueueNotifier
import com.jsm.boardgame.holdem.application.port.TableExecutor
import org.springframework.stereotype.Service

/** 대기열은 인메모리(JoinQueue)라 트랜잭션이 필요 없다. command.tableId 는 쓰지 않는다 —
 *  userId 는 전역에서 한 번에 하나의 대기열에만 있을 수 있어 userId 만으로 충분하다.
 *
 *  이 서비스가 직접 [TableExecutor] 를 부른다 — 호출자가 넘긴 tableId 가 아니라 여기서 런타임에
 *  찾은 테이블(joinQueue.tableOf)로 실행기를 골라야 해서다. */
@Service
class CancelJoinRequestService(
    private val joinQueue: JoinQueue,
    private val notifier: JoinQueueNotifier,
    private val tableExecutor: TableExecutor,
) : CancelJoinRequestUseCase {

    override fun cancel(command: CancelJoinRequestCommand) {
        val tableId = joinQueue.tableOf(UserId(command.userId))
            ?: throw JoinRequestNotFoundException("취소할 참가 요청이 없습니다: userId=${command.userId}")
        tableExecutor.call(tableId) {
            joinQueue.removeByUserId(UserId(command.userId))
                ?: throw JoinRequestNotFoundException("취소할 참가 요청이 없습니다: userId=${command.userId}")
            notifier.notifyPositions(tableId, joinQueue.entriesOf(tableId))
        }
    }
}
