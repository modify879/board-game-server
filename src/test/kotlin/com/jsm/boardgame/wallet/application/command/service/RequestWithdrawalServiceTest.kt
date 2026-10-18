package com.jsm.boardgame.wallet.application.command.service

import com.jsm.boardgame.wallet.application.config.WalletProperties
import com.jsm.boardgame.wallet.application.command.usecase.RequestWithdrawalCommand
import com.jsm.boardgame.wallet.domain.exception.InsufficientBalanceException
import com.jsm.boardgame.wallet.domain.exception.WalletErrorCode
import com.jsm.boardgame.wallet.domain.model.LedgerEntryType
import com.jsm.boardgame.wallet.domain.model.LedgerReferenceType
import com.jsm.boardgame.wallet.domain.model.Money
import com.jsm.boardgame.wallet.domain.model.Wallet
import com.jsm.boardgame.wallet.domain.model.WalletId
import com.jsm.boardgame.wallet.domain.model.UserId
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import com.jsm.boardgame.wallet.domain.repository.FakeLedgerEntryRepository
import com.jsm.boardgame.wallet.domain.repository.FakeWalletRepository
import com.jsm.boardgame.wallet.domain.repository.FakeWithdrawalRequestRepository

class RequestWithdrawalServiceTest {

    private val withdrawalRequests = FakeWithdrawalRequestRepository()
    private val wallets = FakeWalletRepository()
    private val ledger = FakeLedgerEntryRepository()
    private val clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC)
    private val service = RequestWithdrawalService(withdrawalRequests, wallets, ledger, clock, WalletProperties(1_000, 1_000))

    private fun walletWithBalance(userId: Long, balance: Long) {
        wallets.stored[userId] = Wallet.reconstitute(id = WalletId(userId), userId = UserId(userId), balance = Money.of(balance))
    }

    @Test
    fun `요청하면 잔액이 즉시 줄고 WITHDRAWAL_HOLD 엔트리가 생기며 reference 가 요청을 가리킨다`() {
        walletWithBalance(1, 10_000)

        val id = service.request(RequestWithdrawalCommand(1, 5_000, "국민은행", "123456789012", "홍길동", "key"))

        assertEquals(Money.of(5_000), wallets.findByUserId(UserId(1))!!.balance)
        val entry = ledger.stored.single()
        assertEquals(LedgerEntryType.WITHDRAWAL_HOLD, entry.type)
        assertEquals(LedgerReferenceType.WITHDRAWAL_REQUEST, entry.reference.type)
        assertEquals(id.value, entry.reference.id)
    }

    @Test
    fun `잔액보다 큰 금액이면 INSUFFICIENT_BALANCE`() {
        walletWithBalance(1, 1_000)

        val e = assertFailsWith<InsufficientBalanceException> {
            service.request(RequestWithdrawalCommand(1, 5_000, "국민은행", "123456789012", "홍길동", "key"))
        }
        assertEquals(WalletErrorCode.INSUFFICIENT_BALANCE, e.errorCode)
    }

    @Test
    fun `지갑이 없는 사용자가 요청하면 INSUFFICIENT_BALANCE`() {
        val e = assertFailsWith<InsufficientBalanceException> {
            service.request(RequestWithdrawalCommand(1, 5_000, "국민은행", "123456789012", "홍길동", "key"))
        }
        assertEquals(WalletErrorCode.INSUFFICIENT_BALANCE, e.errorCode)
    }

    @Test
    fun `최소액은 설정값을 따른다 - 미달이면 지갑을 건드리지 않는다`() {
        walletWithBalance(1, 10_000)
        val strict = RequestWithdrawalService(withdrawalRequests, wallets, ledger, clock, WalletProperties(1_000, 5_000))

        val e = assertFailsWith<com.jsm.boardgame.wallet.domain.exception.InvalidWithdrawalAmountException> {
            strict.request(RequestWithdrawalCommand(1, 4_000, "국민은행", "123456789012", "홍길동", "key"))
        }

        assertEquals(WalletErrorCode.WITHDRAWAL_AMOUNT_INVALID, e.errorCode)
        assertEquals(Money.of(10_000), wallets.findByUserId(UserId(1))!!.balance)
        service.request(RequestWithdrawalCommand(1, 4_000, "국민은행", "123456789012", "홍길동", "key2"))
    }
}
