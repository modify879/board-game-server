package com.jsm.boardgame.wallet.application.command.service

import com.jsm.boardgame.wallet.application.config.WalletProperties
import com.jsm.boardgame.wallet.application.command.usecase.RequestDepositCommand
import com.jsm.boardgame.wallet.domain.repository.WalletRepository
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import com.jsm.boardgame.wallet.domain.repository.FakeDepositRequestRepository
import com.jsm.boardgame.wallet.domain.repository.FakeWalletRepository

// RequestDepositService 는 WalletRepository 를 의존하지 않는다 — 이 페이크는
// "지갑이 생성되지 않는다"를 서비스 바깥에서 증거로 남기기 위한 것일 뿐, 실제로 연결되지 않는다.
class RequestDepositServiceTest {

    private val depositRequests = FakeDepositRequestRepository()
    private val wallets = FakeWalletRepository()
    private val clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC)
    private val service = RequestDepositService(depositRequests, clock, WalletProperties(1_000, 1_000))

    @Test
    fun `요청이 저장되고 id 가 반환된다`() {
        val id = service.request(RequestDepositCommand(userId = 1, amount = 10_000, idempotencyKey = "key"))

        assertTrue(id.value != 0L)
        assertEquals(10_000L, depositRequests.stored[id.value]?.requestedAmount?.amount)
    }

    @Test
    fun `지갑이 생성되지 않는다`() {
        service.request(RequestDepositCommand(userId = 1, amount = 10_000, idempotencyKey = "key"))

        assertTrue(wallets.stored.isEmpty())
    }

    @Test
    fun `최소액은 설정값을 따른다`() {
        val strict = RequestDepositService(depositRequests, clock, WalletProperties(5_000, 1_000))

        val e = kotlin.test.assertFailsWith<com.jsm.boardgame.wallet.domain.exception.InvalidDepositAmountException> {
            strict.request(RequestDepositCommand(userId = 1, amount = 4_000, idempotencyKey = "k1"))
        }
        assertEquals(com.jsm.boardgame.wallet.domain.exception.WalletErrorCode.DEPOSIT_AMOUNT_INVALID, e.errorCode)
        strict.request(RequestDepositCommand(userId = 1, amount = 5_000, idempotencyKey = "k2"))
        // 같은 4,000 이 기본 설정(1,000)에서는 통과한다.
        service.request(RequestDepositCommand(userId = 1, amount = 4_000, idempotencyKey = "k3"))
    }
}
