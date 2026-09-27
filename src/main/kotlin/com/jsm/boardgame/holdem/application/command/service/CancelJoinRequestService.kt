package com.jsm.boardgame.holdem.application.command.service

import com.jsm.boardgame.holdem.application.command.usecase.CancelJoinRequestCommand
import com.jsm.boardgame.holdem.application.command.usecase.CancelJoinRequestUseCase
import com.jsm.boardgame.holdem.application.exception.JoinRequestNotFoundException
import com.jsm.boardgame.holdem.application.port.JoinQueue
import com.jsm.boardgame.holdem.application.port.JoinQueueNotifier
import com.jsm.boardgame.holdem.application.port.TableExecutor
import org.springframework.stereotype.Service

/** 대기열은 이제 DB 가 아니라 인메모리(JoinQueue)라 트랜잭션이 필요 없다. command.tableId 는
 *  쓰지 않는다 — 옛 CancelJoinRequestService 도 테이블별로 검증하지 않았다(HoldemTable.cancelJoinRequest
 *  가 userId 로만 찾았다). userId 는 전역에서 한 번에 하나의 대기열에만 있을 수 있어 그걸로 충분하다.
 *
 *  이 서비스가 직접 [TableExecutor] 를 부른다 — 호출자가 넘긴 tableId 가 아니라 여기서 런타임에
 *  찾은 테이블(joinQueue.tableOf)로 실행기를 골라야 해서다. 테이블의 실행기 스레드가 하나뿐이라,
 *  같은 테이블에 대한 이 취소와 진행 중인 ProcessJoinRequestsService 의 착석 처리는 항상 그 스레드
 *  위에서 순서대로만 실행된다 — 더 이상 JoinQueue.withTableLock 으로 따로 직렬화할 필요가 없다. */
@Service
class CancelJoinRequestService(
    private val joinQueue: JoinQueue,
    private val notifier: JoinQueueNotifier,
    private val tableExecutor: TableExecutor,
) : CancelJoinRequestUseCase {

    override fun cancel(command: CancelJoinRequestCommand) {
        val tableId = joinQueue.tableOf(command.userId)
            ?: throw JoinRequestNotFoundException("취소할 참가 요청이 없습니다: userId=${command.userId}")
        tableExecutor.call(tableId) {
            joinQueue.removeByUserId(command.userId)
                ?: throw JoinRequestNotFoundException("취소할 참가 요청이 없습니다: userId=${command.userId}")
            notifier.notifyPositions(tableId, joinQueue.entriesOf(tableId))
        }
    }
}
