package com.jsm.boardgame.wallet.application.command.service

import com.jsm.boardgame.wallet.application.command.usecase.TransferBalanceToGameCommand
import com.jsm.boardgame.wallet.application.command.usecase.TransferBalanceToGameUseCase
import com.jsm.boardgame.wallet.domain.model.LedgerEntryType
import com.jsm.boardgame.wallet.domain.model.LedgerReference
import com.jsm.boardgame.wallet.domain.model.LedgerReferenceType
import com.jsm.boardgame.wallet.domain.model.UserId
import com.jsm.boardgame.wallet.domain.repository.LedgerEntryRepository
import com.jsm.boardgame.wallet.domain.repository.WalletRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant

/**
 * 지갑 잔액 전부를 게임 테이블로 옮긴다(홀덤 착석·핸드 시작 충전). 기본 전파(REQUIRED)라
 * 호출자 트랜잭션과 원자적이다. 잠근 지갑에서 잔액을 읽고 그만큼 옮기므로 잔액 부족이 날 수 없다 —
 * [TransferToGameService] 로 읽고-이체 사이에 출금이 끼면 핸드 시작 트랜잭션이 rollback-only 가 되어 테이블이 멈춘다.
 */
@Service
@Transactional
class TransferBalanceToGameService(
    private val wallets: WalletRepository,
    private val ledger: LedgerEntryRepository,
    private val clock: Clock,
) : TransferBalanceToGameUseCase {

    override fun transfer(command: TransferBalanceToGameCommand): Long {
        val wallet = wallets.findOrOpen(UserId(command.userId))
        val amount = wallet.balance
        if (amount.amount == 0L) return 0
        val entry = wallet.record(
            type = LedgerEntryType.GAME_BUY_IN,
            amount = amount,
            reference = LedgerReference(LedgerReferenceType.GAME_TABLE, command.gameTableId),
            memo = command.memo,
            at = Instant.now(clock),
        )
        wallets.save(wallet)
        ledger.save(entry)
        return amount.amount
    }
}
