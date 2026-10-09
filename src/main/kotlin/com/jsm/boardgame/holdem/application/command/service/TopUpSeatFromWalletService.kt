package com.jsm.boardgame.holdem.application.command.service

import com.jsm.boardgame.holdem.domain.model.UserId
import com.jsm.boardgame.holdem.application.command.usecase.TopUpSeatFromWalletCommand
import com.jsm.boardgame.holdem.application.command.usecase.TopUpSeatFromWalletUseCase
import com.jsm.boardgame.holdem.application.event.HandBroadcastRequested
import com.jsm.boardgame.holdem.application.port.HandStore
import com.jsm.boardgame.holdem.application.port.TableExecutor
import com.jsm.boardgame.holdem.application.port.WalletTransfer
import com.jsm.boardgame.holdem.domain.model.TableId
import com.jsm.boardgame.holdem.domain.repository.HoldemTableRepository
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionOperations

/**
 * 착석 중 지갑에 돈이 들어오면(WalletCredited) 핸드가 없을 때 바로 스택에 합치고 공개 뷰를 내보낸다.
 * 핸드가 진행 중이면 아무것도 하지 않는다 — 그 판 스택은 불변이고(table stakes), 정산 직후
 * HandSettler 가 합친다.
 *
 * 이 서비스가 직접 [TableExecutor] 를 부르고 그 안에서 트랜잭션을 연다 — 호출자(지갑 커밋 후
 * 리스너)는 사용자가 앉은 테이블을 모르기 때문이다(CancelJoinRequestService 와 같은 이유). 트랜잭션을
 * 실행기 스레드 안에서 열어야 하므로 @Transactional 대신 [TransactionOperations] 를 쓴다.
 * 테이블 재조회 뒤에 확인하는 이유: 테이블을 찾은 시점과 실행기 차례가 오는 시점 사이에 기립했을 수 있다.
 */
@Service
class TopUpSeatFromWalletService(
    private val tables: HoldemTableRepository,
    private val handStore: HandStore,
    private val walletTransfer: WalletTransfer,
    private val eventPublisher: ApplicationEventPublisher,
    private val tableExecutor: TableExecutor,
    private val transactions: TransactionOperations,
) : TopUpSeatFromWalletUseCase {

    override fun topUp(command: TopUpSeatFromWalletCommand) {
        val tableId = tables.findByUserId(UserId(command.userId))?.id ?: return
        tableExecutor.call(tableId) { transactions.executeWithoutResult { topUpInTable(tableId, UserId(command.userId)) } }
    }

    private fun topUpInTable(tableId: TableId, userId: UserId) {
        val table = tables.findById(tableId) ?: return
        val seat = table.seatOf(userId) ?: return
        if (handStore.find(tableId) != null) return

        if (walletTransfer.topUpSeat(tableId, table, seat.seatNo, userId)) {
            tables.save(table)
            eventPublisher.publishEvent(HandBroadcastRequested(tableId, table, null))
        }
    }
}
