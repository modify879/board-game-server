package com.jsm.boardgame.holdem.application.command.service

import com.jsm.boardgame.holdem.domain.model.UserId
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
 * 대기열 맨 앞 항목 하나를 자기만의 트랜잭션으로 착석시킨다. REQUIRES_NEW 가 필요한 이유는 둘이다.
 * 첫째, 호출자 ProcessJoinRequestsService 는 항목마다 이 메서드를 부르는 루프라서, 항목 하나의
 * 지갑 이체가 실패해도 먼저 착석한 항목의 커밋을 되돌리면 안 된다 — 항목마다 트랜잭션이 따로여야 한다.
 * 둘째, 그 루프는 JoinRequestsProcessor(@TransactionalEventListener, 기본 phase=AFTER_COMMIT)가
 * TableExecutor 스레드에 올려 실행하므로 커밋이 끝난 뒤에 돈다. 이 시점에는 이미 커밋된 바깥
 * 트랜잭션의 리소스가 스레드에 남아 있을 수 있어, 기본 REQUIRED 는 그 트랜잭션에 "참가"하려다
 * "No active transaction" 으로 터진다. 새 트랜잭션을 여는 REQUIRES_NEW 는 둘 다 피한다.
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

        if (tables.findByUserId(UserId(command.userId)) != null) {
            throw AlreadySeatedException("다른 테이블에 이미 앉아 있습니다: userId=${command.userId}")
        }

        // 잔액이 BB 미만이면 sitDown 이 BuyInOutOfRange 로 던지고 REQUIRES_NEW 가 이체까지 롤백한다.
        val amount = walletTransfer.allToGame(UserId(command.userId), tableId, memo = "holdem")
        table.sitDown(UserId(command.userId), Chips.of(amount), command.postBlindImmediately)
        val seatNo = table.seatOf(UserId(command.userId))!!.seatNo
        handStarter.rescheduleOnEntry(tableId, table)
        return AdmitJoinRequestResult.Seated(seatNo)
    }
}
