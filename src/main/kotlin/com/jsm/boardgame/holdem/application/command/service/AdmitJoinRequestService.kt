package com.jsm.boardgame.holdem.application.command.service

import com.jsm.boardgame.holdem.application.command.usecase.AdmitJoinRequestCommand
import com.jsm.boardgame.holdem.application.command.usecase.AdmitJoinRequestUseCase
import com.jsm.boardgame.holdem.application.exception.TableNotFoundException
import com.jsm.boardgame.holdem.application.port.HandStore
import com.jsm.boardgame.holdem.application.port.WalletTransfer
import com.jsm.boardgame.holdem.domain.exception.AlreadySeatedException
import com.jsm.boardgame.holdem.domain.model.TableId
import com.jsm.boardgame.holdem.domain.repository.HoldemTableRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/**
 * 참가 요청 하나를 자기만의 트랜잭션으로 착석시킨다(SitDownService 의 착석 분기와 같은 순서 —
 * 도메인 검증 → 지갑 차감 → 카운트다운 리셋). 실패하면 이 트랜잭션만 롤백되고 다른 요청·정산에는
 * 영향이 없다 — HandSettler 안에서 처리하다가 지갑 실패가 정산 전체를 rollback-only 로 만들던
 * 문제(UnexpectedRollbackException)를 격리한 이유다.
 *
 * 핸드가 이미 진행 중이면(그 사이 새 핸드가 시작됨) 이 요청은 건드리지 않고 그대로 둔다 —
 * 다음 핸드가 끝날 때 다시 시도된다.
 */
@Service
// REQUIRES_NEW 가 필요하다 — 이 메서드를 부르는 JoinRequestsProcessor 는
// @TransactionalEventListener(AFTER_COMMIT) 라서, 실행 시점에 방금 커밋된 바깥 트랜잭션의 리소스가
// 아직 스레드에 바인딩된 채로 남아 있다(모든 afterCommit 동기화가 끝나야 해제된다). 기본 REQUIRED 를
// 쓰면 이미 커밋된 그 트랜잭션에 "참가"하려다 실제 DB 작업에서 "No active transaction" 으로 터진다.
@Transactional(propagation = Propagation.REQUIRES_NEW)
class AdmitJoinRequestService(
    private val tables: HoldemTableRepository,
    private val handStore: HandStore,
    private val walletTransfer: WalletTransfer,
    private val handStarter: HandStarter,
) : AdmitJoinRequestUseCase {

    override fun admit(command: AdmitJoinRequestCommand) {
        val tableId = TableId(command.tableId)
        val table = tables.findById(tableId)
            ?: throw TableNotFoundException("존재하지 않는 테이블입니다: tableId=${command.tableId}")

        if (handStore.find(tableId) != null) return

        val request = table.pendingJoinRequests().find { it.userId == command.userId } ?: return

        if (tables.findByUserId(request.userId) != null) {
            throw AlreadySeatedException("다른 테이블에 이미 앉아 있습니다: userId=${request.userId}")
        }

        table.sitDown(request.seatNo, request.userId, request.buyIn, request.postBlindImmediately)
        walletTransfer.toGame(request.userId, request.buyIn.amount, tableId.value, memo = "holdem")
        table.consumeJoinRequest(request.seatNo)
        handStarter.rescheduleOnEntry(tableId, table)
    }
}
