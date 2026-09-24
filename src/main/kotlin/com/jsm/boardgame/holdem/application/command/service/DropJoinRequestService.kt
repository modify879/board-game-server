package com.jsm.boardgame.holdem.application.command.service

import com.jsm.boardgame.holdem.application.command.usecase.DropJoinRequestCommand
import com.jsm.boardgame.holdem.application.command.usecase.DropJoinRequestUseCase
import com.jsm.boardgame.holdem.domain.model.TableId
import com.jsm.boardgame.holdem.domain.repository.HoldemTableRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/** 실패한 참가 요청을 버린다. AdmitJoinRequestService 가 비즈니스 예외로 실패했을 때 이 요청이
 *  좌석을 영구히 막지 않도록 ProcessJoinRequestsService 가 별도 트랜잭션으로 부른다. */
@Service
// REQUIRES_NEW 가 필요하다 — 이 메서드를 부르는 JoinRequestsProcessor 는
// @TransactionalEventListener(AFTER_COMMIT) 라서, 실행 시점에 방금 커밋된 바깥 트랜잭션의 리소스가
// 아직 스레드에 바인딩된 채로 남아 있다(모든 afterCommit 동기화가 끝나야 해제된다). 기본 REQUIRED 를
// 쓰면 이미 커밋된 그 트랜잭션에 "참가"하려다 이 메서드의 tables.save(table) 에서
// "No active transaction" 으로 터진다.
@Transactional(propagation = Propagation.REQUIRES_NEW)
class DropJoinRequestService(
    private val tables: HoldemTableRepository,
) : DropJoinRequestUseCase {

    override fun drop(command: DropJoinRequestCommand) {
        val table = tables.findById(TableId(command.tableId)) ?: return
        val request = table.pendingJoinRequests().find { it.userId == command.userId } ?: return
        table.consumeJoinRequest(request.seatNo)
        tables.save(table)
    }
}
