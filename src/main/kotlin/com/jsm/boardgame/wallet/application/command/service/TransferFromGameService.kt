package com.jsm.boardgame.wallet.application.command.service

import com.jsm.boardgame.wallet.application.command.usecase.TransferFromGameCommand
import com.jsm.boardgame.wallet.application.command.usecase.TransferFromGameUseCase
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
 * 홀덤 등 게임의 캐시아웃 입구다. 게임 테이블에서 지갑으로 칩을 옮긴다. 지갑이 없으면 lazy 로 만든다.
 *
 * 기본 전파(REQUIRED)를 그대로 쓴다 — REQUIRES_NEW 로 새 트랜잭션을 열면 호출자(게임 퇴장
 * 유스케이스)의 트랜잭션과 분리되어 지갑 적립과 퇴장 처리가 원자적이지 않게 된다.
 */
@Service
@Transactional
class TransferFromGameService(
    private val wallets: WalletRepository,
    private val ledger: LedgerEntryRepository,
    private val clock: Clock,
) : TransferFromGameUseCase {

    override fun transfer(command: TransferFromGameCommand) {
        val amount = gameTransferMoney(command.amount)
        val wallet = wallets.findOrOpen(command.userId)
        val entry = wallet.record(
            type = LedgerEntryType.GAME_CASH_OUT,
            amount = amount,
            reference = LedgerReference(LedgerReferenceType.GAME_TABLE, command.gameTableId),
            memo = command.memo,
            at = Instant.now(clock),
        )
        wallets.save(wallet)
        ledger.save(entry)
    }
}
