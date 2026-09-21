package com.jsm.boardgame.wallet.application.command.service

import com.jsm.boardgame.wallet.application.command.usecase.TransferToGameCommand
import com.jsm.boardgame.wallet.application.command.usecase.TransferToGameUseCase
import com.jsm.boardgame.wallet.domain.model.LedgerEntryType
import com.jsm.boardgame.wallet.domain.model.LedgerReference
import com.jsm.boardgame.wallet.domain.model.LedgerReferenceType
import com.jsm.boardgame.wallet.domain.model.gameTransferMoney
import com.jsm.boardgame.wallet.domain.repository.LedgerEntryRepository
import com.jsm.boardgame.wallet.domain.repository.WalletRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant

/**
 * 홀덤 등 게임의 바이인 입구다. 지갑에서 게임 테이블로 칩을 옮긴다. 지갑이 없으면 lazy 로 만든다.
 *
 * 기본 전파(REQUIRED)를 그대로 쓴다 — REQUIRES_NEW 로 새 트랜잭션을 열면 호출자(게임 착석
 * 유스케이스)의 트랜잭션과 분리되어 지갑 차감과 착석이 원자적이지 않게 된다. 잔액이 부족하면
 * wallet.record() 가 InsufficientBalanceException 을 던지고 호출자 트랜잭션 전체가 롤백된다.
 */
@Service
@Transactional
class TransferToGameService(
    private val wallets: WalletRepository,
    private val ledger: LedgerEntryRepository,
    private val clock: Clock,
) : TransferToGameUseCase {

    override fun transfer(command: TransferToGameCommand) {
        val amount = gameTransferMoney(command.amount)
        val wallet = wallets.findOrOpen(command.userId)
        val entry = wallet.record(
            type = LedgerEntryType.GAME_BUY_IN,
            amount = amount,
            reference = LedgerReference(LedgerReferenceType.GAME_TABLE, command.gameTableId),
            memo = command.memo,
            at = Instant.now(clock),
        )
        wallets.save(wallet)
        ledger.save(entry)
    }
}
