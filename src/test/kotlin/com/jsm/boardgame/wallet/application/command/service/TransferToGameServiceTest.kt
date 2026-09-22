package com.jsm.boardgame.wallet.application.command.service

import com.jsm.boardgame.wallet.application.command.usecase.TransferToGameCommand
import com.jsm.boardgame.wallet.domain.exception.InsufficientBalanceException
import com.jsm.boardgame.wallet.domain.exception.InvalidAmountException
import com.jsm.boardgame.wallet.domain.exception.WalletErrorCode
import com.jsm.boardgame.wallet.domain.model.LedgerEntry
import com.jsm.boardgame.wallet.domain.model.LedgerEntryId
import com.jsm.boardgame.wallet.domain.model.LedgerEntryType
import com.jsm.boardgame.wallet.domain.model.LedgerReferenceType
import com.jsm.boardgame.wallet.domain.model.Money
import com.jsm.boardgame.wallet.domain.model.Wallet
import com.jsm.boardgame.wallet.domain.model.WalletId
import com.jsm.boardgame.wallet.domain.repository.LedgerEntryRepository
import com.jsm.boardgame.wallet.domain.repository.WalletRepository
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

private class TransferToGameFakeWalletRepository : WalletRepository {
    val stored = mutableMapOf<Long, Wallet>()
    private var sequence = 0L

    override fun findByUserId(userId: Long): Wallet? = stored[userId]

    override fun save(wallet: Wallet): Wallet {
        val saved = if (wallet.id == null) {
            sequence += 1
            Wallet.reconstitute(id = WalletId(sequence), userId = wallet.userId, balance = wallet.balance, version = wallet.version)
        } else {
            wallet
        }
        stored[wallet.userId] = saved
        return saved
    }

    override fun findOrOpen(userId: Long): Wallet = findByUserId(userId) ?: save(Wallet.open(userId))
}

private class TransferToGameFakeLedgerEntryRepository : LedgerEntryRepository {
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

class TransferToGameServiceTest {

    private val wallets = TransferToGameFakeWalletRepository()
    private val ledger = TransferToGameFakeLedgerEntryRepository()
    private val clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC)
    private val service = TransferToGameService(wallets, ledger, clock)

    @Test
    fun `바이인이면 잔액이 줄고 GAME_BUY_IN 엔트리가 balanceAfter 와 함께 남는다`() {
        wallets.stored[1] = Wallet.reconstitute(id = WalletId(1), userId = 1, balance = Money.of(10_000), version = 0)

        service.transfer(TransferToGameCommand(userId = 1, amount = 3_000, gameTableId = 7, memo = "holdem"))

        val entry = ledger.stored.single()
        assertEquals(LedgerEntryType.GAME_BUY_IN, entry.type)
        assertEquals(Money.of(3_000), entry.amount)
        assertEquals(Money.of(7_000), entry.balanceAfter)
        assertEquals(Money.of(7_000), wallets.findByUserId(1)!!.balance)
    }

    @Test
    fun `reference 가 GAME_TABLE 타입과 gameTableId 를 담는다`() {
        wallets.stored[1] = Wallet.reconstitute(id = WalletId(1), userId = 1, balance = Money.of(10_000), version = 0)

        service.transfer(TransferToGameCommand(userId = 1, amount = 1_000, gameTableId = 42, memo = null))

        val entry = ledger.stored.single()
        assertEquals(LedgerReferenceType.GAME_TABLE, entry.reference.type)
        assertEquals(42L, entry.reference.id)
    }

    @Test
    fun `잔액보다 큰 바이인이면 INSUFFICIENT_BALANCE`() {
        wallets.stored[1] = Wallet.reconstitute(id = WalletId(1), userId = 1, balance = Money.of(1_000), version = 0)

        val e = assertFailsWith<InsufficientBalanceException> {
            service.transfer(TransferToGameCommand(userId = 1, amount = 5_000, gameTableId = 7, memo = null))
        }
        assertEquals(WalletErrorCode.INSUFFICIENT_BALANCE, e.errorCode)
    }

    @Test
    fun `음수 금액이면 AMOUNT_NEGATIVE`() {
        val e = assertFailsWith<InvalidAmountException> {
            service.transfer(TransferToGameCommand(userId = 1, amount = -100, gameTableId = 7, memo = null))
        }
        assertEquals(WalletErrorCode.AMOUNT_NEGATIVE, e.errorCode)
    }

    @Test
    fun `100원 단위가 아니면 GAME_TRANSFER_AMOUNT_INVALID`() {
        val e = assertFailsWith<InvalidAmountException> {
            service.transfer(TransferToGameCommand(userId = 1, amount = 1_050, gameTableId = 7, memo = null))
        }
        assertEquals(WalletErrorCode.GAME_TRANSFER_AMOUNT_INVALID, e.errorCode)
    }

    @Test
    fun `지갑이 없던 사용자도 바이인하면 지갑이 lazy 로 만들어지고 잔액이 부족해 INSUFFICIENT_BALANCE 가 난다`() {
        val e = assertFailsWith<InsufficientBalanceException> {
            service.transfer(TransferToGameCommand(userId = 42, amount = 1_000, gameTableId = 7, memo = null))
        }
        assertEquals(WalletErrorCode.INSUFFICIENT_BALANCE, e.errorCode)
        assertEquals(Money.ZERO, wallets.findByUserId(42)!!.balance)
    }
}
