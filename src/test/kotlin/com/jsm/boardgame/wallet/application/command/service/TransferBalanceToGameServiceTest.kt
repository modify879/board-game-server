package com.jsm.boardgame.wallet.application.command.service

import com.jsm.boardgame.wallet.application.command.usecase.TransferBalanceToGameCommand
import com.jsm.boardgame.wallet.domain.model.LedgerEntry
import com.jsm.boardgame.wallet.domain.model.LedgerEntryId
import com.jsm.boardgame.wallet.domain.model.LedgerEntryType
import com.jsm.boardgame.wallet.domain.model.LedgerReferenceType
import com.jsm.boardgame.wallet.domain.model.Money
import com.jsm.boardgame.wallet.domain.model.Wallet
import com.jsm.boardgame.wallet.domain.model.WalletId
import com.jsm.boardgame.wallet.domain.model.UserId
import com.jsm.boardgame.wallet.domain.repository.LedgerEntryRepository
import com.jsm.boardgame.wallet.domain.repository.WalletRepository
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class TransferBalanceToGameFakeWalletRepository : WalletRepository {
    val stored = mutableMapOf<Long, Wallet>()
    private var sequence = 0L

    override fun findByUserId(userId: UserId): Wallet? = stored[userId.value]

    override fun save(wallet: Wallet): Wallet {
        val saved = if (wallet.id == null) {
            sequence += 1
            Wallet.reconstitute(id = WalletId(sequence), userId = wallet.userId, balance = wallet.balance)
        } else {
            wallet
        }
        stored[wallet.userId.value] = saved
        return saved
    }

    override fun findOrOpen(userId: UserId): Wallet = findByUserId(userId) ?: save(Wallet.open(userId))
}

private class TransferBalanceToGameFakeLedgerEntryRepository : LedgerEntryRepository {
    val stored = mutableListOf<LedgerEntry>()
    private var sequence = 0L

    override fun save(entry: LedgerEntry): LedgerEntry {
        sequence += 1
        val saved = LedgerEntry.reconstitute(
            id = LedgerEntryId(sequence),
            walletId = entry.walletId,
            type = entry.type,
            amount = entry.amount,
            balanceAfter = entry.balanceAfter,
            reference = entry.reference,
            memo = entry.memo,
            occurredAt = entry.occurredAt,
        )
        stored += saved
        return saved
    }
}

class TransferBalanceToGameServiceTest {

    private val wallets = TransferBalanceToGameFakeWalletRepository()
    private val ledger = TransferBalanceToGameFakeLedgerEntryRepository()
    private val clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC)
    private val service = TransferBalanceToGameService(wallets, ledger, clock)

    @Test
    fun `잔액 전부를 옮기면 잔액이 0 이 되고 GAME_BUY_IN 엔트리 한 건이 남는다`() {
        wallets.stored[1] = Wallet.reconstitute(id = WalletId(1), userId = UserId(1), balance = Money.of(12_300))

        val moved = service.transfer(TransferBalanceToGameCommand(userId = 1, gameTableId = 7, memo = "holdem"))

        assertEquals(12_300L, moved)
        assertEquals(Money.ZERO, wallets.findByUserId(UserId(1))!!.balance)
        val entry = ledger.stored.single()
        assertEquals(LedgerEntryType.GAME_BUY_IN, entry.type)
        assertEquals(Money.of(12_300), entry.amount)
        assertEquals(Money.ZERO, entry.balanceAfter)
        assertEquals(LedgerReferenceType.GAME_TABLE, entry.reference.type)
        assertEquals(7L, entry.reference.id)
    }

    @Test
    fun `잔액이 0 이면 원장을 남기지 않고 0 을 돌려준다`() {
        wallets.stored[1] = Wallet.reconstitute(id = WalletId(1), userId = UserId(1), balance = Money.ZERO)

        val moved = service.transfer(TransferBalanceToGameCommand(userId = 1, gameTableId = 7, memo = null))

        assertEquals(0L, moved)
        assertTrue(ledger.stored.isEmpty())
    }

    @Test
    fun `지갑이 없던 사용자는 지갑이 lazy 로 만들어지고 0 을 돌려준다`() {
        val moved = service.transfer(TransferBalanceToGameCommand(userId = 42, gameTableId = 7, memo = null))

        assertEquals(0L, moved)
        assertEquals(Money.ZERO, wallets.findByUserId(UserId(42))!!.balance)
        assertTrue(ledger.stored.isEmpty())
    }
}
