package com.jsm.boardgame.wallet.application.command.service

import com.jsm.boardgame.wallet.application.config.WalletProperties
import com.jsm.boardgame.wallet.application.command.usecase.RequestDepositCommand
import com.jsm.boardgame.wallet.application.port.UserExistence
import com.jsm.boardgame.wallet.domain.exception.WalletErrorCode
import com.jsm.boardgame.wallet.domain.exception.WalletOwnerNotFoundException
import com.jsm.boardgame.wallet.domain.model.Money
import com.jsm.boardgame.wallet.domain.model.UserId
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import com.jsm.boardgame.wallet.domain.repository.FakeDepositRequestRepository
import com.jsm.boardgame.wallet.domain.repository.FakeWalletRepository

class RequestDepositServiceTest {

    private val depositRequests = FakeDepositRequestRepository()
    private val wallets = FakeWalletRepository()
    private val withdrawnUserIds = mutableSetOf<Long>()
    private val userExistence = UserExistence { it.value !in withdrawnUserIds }
    private val clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC)
    private val service = RequestDepositService(depositRequests, wallets, userExistence, clock, WalletProperties(1_000, 1_000))

    @Test
    fun `요청이 저장되고 id 가 반환된다`() {
        val id = service.request(RequestDepositCommand(userId = 1, amount = 10_000, idempotencyKey = "key"))

        assertTrue(id != 0L)
        assertEquals(10_000L, depositRequests.stored[id]?.requestedAmount?.amount)
    }

    @Test
    fun `지갑 락을 잡으려고 지갑을 열지만 잔액은 승인 전까지 그대로다`() {
        service.request(RequestDepositCommand(userId = 1, amount = 10_000, idempotencyKey = "key"))

        assertEquals(Money.ZERO, wallets.findByUserId(UserId(1))!!.balance)
    }

    @Test
    fun `지갑 락을 잡은 뒤 보니 탈퇴한 사용자면 WALLET_OWNER_NOT_FOUND 이고 요청이 남지 않는다`() {
        withdrawnUserIds += 1

        val e = assertFailsWith<WalletOwnerNotFoundException> {
            service.request(RequestDepositCommand(userId = 1, amount = 10_000, idempotencyKey = "key"))
        }

        assertEquals(WalletErrorCode.WALLET_OWNER_NOT_FOUND, e.errorCode)
        assertTrue(depositRequests.stored.isEmpty())
    }

    @Test
    fun `최소액은 설정값을 따른다`() {
        val strict = RequestDepositService(depositRequests, wallets, userExistence, clock, WalletProperties(5_000, 1_000))

        val e = assertFailsWith<com.jsm.boardgame.wallet.domain.exception.InvalidDepositAmountException> {
            strict.request(RequestDepositCommand(userId = 1, amount = 4_000, idempotencyKey = "k1"))
        }
        assertEquals(WalletErrorCode.DEPOSIT_AMOUNT_INVALID, e.errorCode)
        strict.request(RequestDepositCommand(userId = 1, amount = 5_000, idempotencyKey = "k2"))
        // 같은 4,000 이 기본 설정(1,000)에서는 통과한다.
        service.request(RequestDepositCommand(userId = 1, amount = 4_000, idempotencyKey = "k3"))
    }
}
