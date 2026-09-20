package com.jsm.boardgame.wallet.domain.model

import com.jsm.boardgame.wallet.domain.exception.InsufficientBalanceException
import com.jsm.boardgame.wallet.domain.exception.InvalidAmountException
import com.jsm.boardgame.wallet.domain.exception.WalletErrorCode
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class WalletTest {

    private val now = Instant.parse("2026-01-01T00:00:00Z")

    private fun savedWallet(balance: Money = Money.ZERO): Wallet =
        Wallet.reconstitute(id = WalletId(1), userId = 1, balance = balance, version = 0)

    @Test
    fun `open 은 잔액 0, id null 인 지갑을 만든다`() {
        val wallet = Wallet.open(userId = 1)

        assertNull(wallet.id)
        assertEquals(Money.ZERO, wallet.balance)
    }

    @Test
    fun `CREDIT record 가 잔액을 늘리고 balanceAfter 가 그 값이다`() {
        val wallet = savedWallet()

        val entry = wallet.record(
            type = LedgerEntryType.DEPOSIT,
            amount = Money.of(1_000),
            reference = LedgerReference(LedgerReferenceType.DEPOSIT_REQUEST, 1),
            at = now,
        )

        assertEquals(Money.of(1_000), wallet.balance)
        assertEquals(Money.of(1_000), entry.balanceAfter)
    }

    @Test
    fun `DEBIT record 가 잔액을 줄인다`() {
        val wallet = savedWallet(balance = Money.of(1_000))

        val entry = wallet.record(
            type = LedgerEntryType.WITHDRAWAL_HOLD,
            amount = Money.of(300),
            reference = LedgerReference(LedgerReferenceType.WITHDRAWAL_REQUEST, 1),
            at = now,
        )

        assertEquals(Money.of(700), wallet.balance)
        assertEquals(Money.of(700), entry.balanceAfter)
    }

    @Test
    fun `잔액보다 큰 DEBIT 은 INSUFFICIENT_BALANCE 이고 잔액이 바뀌지 않는다`() {
        val wallet = savedWallet(balance = Money.of(100))

        val e = assertFailsWith<InsufficientBalanceException> {
            wallet.record(
                type = LedgerEntryType.WITHDRAWAL_HOLD,
                amount = Money.of(200),
                reference = LedgerReference(LedgerReferenceType.WITHDRAWAL_REQUEST, 1),
                at = now,
            )
        }

        assertEquals(WalletErrorCode.INSUFFICIENT_BALANCE, e.errorCode)
        assertEquals(Money.of(100), wallet.balance)
    }

    @Test
    fun `amount 가 0이면 AMOUNT_NOT_POSITIVE 로 거부된다`() {
        val wallet = savedWallet()

        val e = assertFailsWith<InvalidAmountException> {
            wallet.record(
                type = LedgerEntryType.DEPOSIT,
                amount = Money.ZERO,
                reference = LedgerReference(LedgerReferenceType.DEPOSIT_REQUEST, 1),
                at = now,
            )
        }

        assertEquals(WalletErrorCode.AMOUNT_NOT_POSITIVE, e.errorCode)
    }

    @Test
    fun `저장되지 않은 지갑에 record 하면 IllegalStateException 이 발생한다`() {
        val wallet = Wallet.open(userId = 1)

        assertFailsWith<IllegalStateException> {
            wallet.record(
                type = LedgerEntryType.DEPOSIT,
                amount = Money.of(100),
                reference = LedgerReference(LedgerReferenceType.DEPOSIT_REQUEST, 1),
                at = now,
            )
        }
    }

    @Test
    fun `반환된 LedgerEntry 의 type reference occurredAt 이 인자 그대로다`() {
        val wallet = savedWallet()
        val reference = LedgerReference(LedgerReferenceType.DEPOSIT_REQUEST, 42)

        val entry = wallet.record(
            type = LedgerEntryType.DEPOSIT,
            amount = Money.of(100),
            reference = reference,
            at = now,
        )

        assertEquals(LedgerEntryType.DEPOSIT, entry.type)
        assertEquals(reference, entry.reference)
        assertEquals(now, entry.occurredAt)
    }
}
