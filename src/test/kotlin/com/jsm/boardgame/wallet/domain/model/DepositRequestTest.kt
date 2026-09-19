package com.jsm.boardgame.wallet.domain.model

import com.jsm.boardgame.wallet.domain.exception.DepositRequestAlreadyProcessedException
import com.jsm.boardgame.wallet.domain.exception.InvalidDepositAmountException
import com.jsm.boardgame.wallet.domain.exception.InvalidRejectionReasonException
import com.jsm.boardgame.wallet.domain.exception.NotRequestOwnerException
import com.jsm.boardgame.wallet.domain.exception.WalletErrorCode
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DepositRequestTest {

    private val now = Instant.parse("2026-01-01T00:00:00Z")

    private fun pendingRequest(userId: Long = 1, amount: Long = 10_000): DepositRequest =
        DepositRequest.reconstitute(
            id = DepositRequestId(1),
            userId = userId,
            requestedAmount = Money.of(amount),
            requestedAt = now,
            status = DepositRequestStatus.PENDING,
            creditedAmount = null,
            processedBy = null,
            processedAt = null,
            rejectionReason = null,
            version = 0,
        )

    @Test
    fun `1000원 미만이면 DEPOSIT_AMOUNT_INVALID`() {
        val e = assertFailsWith<InvalidDepositAmountException> {
            DepositRequest.request(userId = 1, amount = Money.of(900), at = now)
        }
        assertEquals(WalletErrorCode.DEPOSIT_AMOUNT_INVALID, e.errorCode)
    }

    @Test
    fun `100원 배수가 아니면 DEPOSIT_AMOUNT_INVALID`() {
        val e = assertFailsWith<InvalidDepositAmountException> {
            DepositRequest.request(userId = 1, amount = Money.of(1_050), at = now)
        }
        assertEquals(WalletErrorCode.DEPOSIT_AMOUNT_INVALID, e.errorCode)
    }

    @Test
    fun `상한은 없다 - 10억도 요청된다`() {
        val request = DepositRequest.request(userId = 1, amount = Money.of(1_000_000_000), at = now)

        assertEquals(Money.of(1_000_000_000), request.requestedAmount)
    }

    @Test
    fun `approve 가 상태 금액 처리자를 채운다`() {
        val request = pendingRequest()

        request.approve(adminUserId = 99, creditedAmount = Money.of(10_000), at = now)

        assertEquals(DepositRequestStatus.APPROVED, request.status)
        assertEquals(Money.of(10_000), request.creditedAmount)
        assertEquals(99L, request.processedBy)
        assertEquals(now, request.processedAt)
    }

    @Test
    fun `두 번 approve 하면 두 번째가 DEPOSIT_REQUEST_ALREADY_PROCESSED`() {
        val request = pendingRequest()
        request.approve(99, Money.of(10_000), now)

        val e = assertFailsWith<DepositRequestAlreadyProcessedException> {
            request.approve(99, Money.of(10_000), now)
        }
        assertEquals(WalletErrorCode.DEPOSIT_REQUEST_ALREADY_PROCESSED, e.errorCode)
    }

    @Test
    fun `reject 후 approve 하면 DEPOSIT_REQUEST_ALREADY_PROCESSED`() {
        val request = pendingRequest()
        request.reject(99, "사유", now)

        val e = assertFailsWith<DepositRequestAlreadyProcessedException> {
            request.approve(99, Money.of(10_000), now)
        }
        assertEquals(WalletErrorCode.DEPOSIT_REQUEST_ALREADY_PROCESSED, e.errorCode)
    }

    @Test
    fun `cancel 후 approve 하면 DEPOSIT_REQUEST_ALREADY_PROCESSED`() {
        val request = pendingRequest()
        request.cancel(1, now)

        val e = assertFailsWith<DepositRequestAlreadyProcessedException> {
            request.approve(99, Money.of(10_000), now)
        }
        assertEquals(WalletErrorCode.DEPOSIT_REQUEST_ALREADY_PROCESSED, e.errorCode)
    }

    @Test
    fun `남의 요청을 cancel 하면 NOT_REQUEST_OWNER`() {
        val request = pendingRequest(userId = 1)

        val e = assertFailsWith<NotRequestOwnerException> {
            request.cancel(requesterUserId = 2, at = now)
        }
        assertEquals(WalletErrorCode.NOT_REQUEST_OWNER, e.errorCode)
    }

    @Test
    fun `reject 사유가 blank 면 REJECTION_REASON_BLANK`() {
        val request = pendingRequest()

        val e = assertFailsWith<InvalidRejectionReasonException> {
            request.reject(99, "   ", now)
        }
        assertEquals(WalletErrorCode.REJECTION_REASON_BLANK, e.errorCode)
    }

    @Test
    fun `approve 의 creditedAmount 는 1000원 미만도 허용되지만 100원 배수여야 한다`() {
        val request = pendingRequest()

        request.approve(99, Money.of(900), now)

        assertEquals(Money.of(900), request.creditedAmount)
    }

    @Test
    fun `approve 의 creditedAmount 가 100원 배수가 아니면 DEPOSIT_AMOUNT_INVALID`() {
        val request = pendingRequest()

        val e = assertFailsWith<InvalidDepositAmountException> {
            request.approve(99, Money.of(950), now)
        }
        assertEquals(WalletErrorCode.DEPOSIT_AMOUNT_INVALID, e.errorCode)
    }
}
