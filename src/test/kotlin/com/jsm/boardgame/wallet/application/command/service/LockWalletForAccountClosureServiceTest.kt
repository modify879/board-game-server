package com.jsm.boardgame.wallet.application.command.service

import com.jsm.boardgame.wallet.domain.model.BankAccount
import com.jsm.boardgame.wallet.domain.model.DepositRequest
import com.jsm.boardgame.wallet.domain.model.Money
import com.jsm.boardgame.wallet.domain.model.UserId
import com.jsm.boardgame.wallet.domain.model.Wallet
import com.jsm.boardgame.wallet.domain.model.WalletId
import com.jsm.boardgame.wallet.domain.model.WithdrawalRequest
import com.jsm.boardgame.wallet.domain.repository.FakeDepositRequestRepository
import com.jsm.boardgame.wallet.domain.repository.FakeWalletRepository
import com.jsm.boardgame.wallet.domain.repository.FakeWithdrawalRequestRepository
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LockWalletForAccountClosureServiceTest {

    private val wallets = FakeWalletRepository()
    private val deposits = FakeDepositRequestRepository()
    private val withdrawals = FakeWithdrawalRequestRepository()
    private val service = LockWalletForAccountClosureService(wallets, deposits, withdrawals)
    private val now = Instant.parse("2026-01-01T00:00:00Z")

    @Test
    fun `지갑이 없으면 열고 비었다고 한다`() {
        assertTrue(service.lockAndCheckEmpty(1))
        assertTrue(wallets.findByUserId(UserId(1)) != null)
    }

    @Test
    fun `잔액이 있으면 비지 않았다`() {
        wallets.stored[1] = Wallet.reconstitute(id = WalletId(1), userId = UserId(1), balance = Money.of(100))

        assertFalse(service.lockAndCheckEmpty(1))
    }

    @Test
    fun `대기 중인 충전 요청이 있으면 비지 않았고 처리되면 비었다`() {
        val saved = deposits.save(DepositRequest.request(UserId(1), 10_000, 1_000, "k", now))
        assertFalse(service.lockAndCheckEmpty(1))

        saved.reject(UserId(9), "사유", now)
        assertTrue(service.lockAndCheckEmpty(1))
    }

    @Test
    fun `대기 중인 환전 요청이 있으면 비지 않았다`() {
        withdrawals.save(WithdrawalRequest.request(UserId(1), 10_000, 1_000, BankAccount.of("국민은행", "123456789012", "홍길동"), "k", now))

        assertFalse(service.lockAndCheckEmpty(1))
    }

    @Test
    fun `다른 사용자의 잔액·요청은 세지 않는다`() {
        wallets.stored[2] = Wallet.reconstitute(id = WalletId(2), userId = UserId(2), balance = Money.of(100))
        deposits.save(DepositRequest.request(UserId(2), 10_000, 1_000, "k", now))

        assertTrue(service.lockAndCheckEmpty(1))
    }
}
