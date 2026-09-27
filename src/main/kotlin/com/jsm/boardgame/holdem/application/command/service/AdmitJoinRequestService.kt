package com.jsm.boardgame.holdem.application.command.service

import com.jsm.boardgame.holdem.application.command.usecase.AdmitJoinRequestCommand
import com.jsm.boardgame.holdem.application.command.usecase.AdmitJoinRequestResult
import com.jsm.boardgame.holdem.application.command.usecase.AdmitJoinRequestUseCase
import com.jsm.boardgame.holdem.application.exception.TableNotFoundException
import com.jsm.boardgame.holdem.application.port.HandStore
import com.jsm.boardgame.holdem.application.port.WalletTransfer
import com.jsm.boardgame.holdem.domain.exception.AlreadySeatedException
import com.jsm.boardgame.holdem.domain.model.Chips
import com.jsm.boardgame.holdem.domain.model.TableId
import com.jsm.boardgame.holdem.domain.repository.HoldemTableRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/**
 * 대기열 맨 앞 항목 하나를 자기만의 트랜잭션으로 착석시킨다. REQUIRES_NEW 가 필요한 이유는
 * DropJoinRequestService(옛) 와 같다 — 이 메서드를 부르는 ProcessJoinRequestsService 는
 * JoinRequestsProcessor(@TransactionalEventListener, 기본 phase=AFTER_COMMIT)에서 실행되어,
 * 실행 시점에 방금 커밋된 바깥 트랜잭션의 리소스가 아직 스레드에 바인딩된 채로 남아 있다
 * (모든 afterCommit 동기화가 끝나야 해제된다). 기본 REQUIRED 를 쓰면 이미 커밋된 그 트랜잭션에
 * "참가"하려다 "No active transaction" 으로 터진다.
 *
 * [tables.save] 시점에 이 트랜잭션이 읽은 버전과 실제 저장된 버전이 다르면 — 그 사이 기립·핸드
 * 시작·접속 상태 갱신 등 다른 트랜잭션이 같은 테이블 행을 먼저 바꿨다는 뜻이다 — `@Version` 이 그
 * 경합을 잡아 ConcurrentTableUpdateException 을 던진다. 이 착석 트랜잭션은 그대로 롤백되고,
 * 호출자(ProcessJoinRequestsService)가 같은 대기열 head 를 다시 시도한다.
 *
 * 핸드가 진행 중이거나 빈 좌석이 없으면 예외 없이 [AdmitJoinRequestResult.Blocked] 를 돌려준다 —
 * 둘 다 "이 항목은 지금 처리할 수 없으니 대기열에 그대로 둔다" 는 뜻이라 호출자(ProcessJoinRequestsService)
 * 가 루프를 멈추는 신호로 쓴다. 그 외 실패(지갑 부족 등)는 BusinessException 을 그대로 던져
 * 호출자가 이 항목을 버리게 한다.
 */
@Service
@Transactional(propagation = Propagation.REQUIRES_NEW)
class AdmitJoinRequestService(
    private val tables: HoldemTableRepository,
    private val handStore: HandStore,
    private val walletTransfer: WalletTransfer,
    private val handStarter: HandStarter,
) : AdmitJoinRequestUseCase {

    override fun admit(command: AdmitJoinRequestCommand): AdmitJoinRequestResult {
        val tableId = TableId(command.tableId)
        val table = tables.findById(tableId)
            ?: throw TableNotFoundException("존재하지 않는 테이블입니다: tableId=${command.tableId}")

        if (handStore.find(tableId) != null) return AdmitJoinRequestResult.Blocked
        if (!table.hasEmptySeat()) return AdmitJoinRequestResult.Blocked

        if (tables.findByUserId(command.userId) != null) {
            throw AlreadySeatedException("다른 테이블에 이미 앉아 있습니다: userId=${command.userId}")
        }

        table.sitDown(command.userId, Chips.of(command.buyIn), command.postBlindImmediately)
        val seatNo = table.seatOf(command.userId)!!.seatNo
        walletTransfer.toGame(command.userId, command.buyIn, tableId.value, memo = "holdem")
        handStarter.rescheduleOnEntry(tableId, table)
        return AdmitJoinRequestResult.Seated(seatNo)
    }
}
