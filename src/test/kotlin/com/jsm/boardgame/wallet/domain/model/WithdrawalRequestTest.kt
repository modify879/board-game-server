package com.jsm.boardgame.wallet.domain.model

import com.jsm.boardgame.wallet.domain.exception.InvalidRejectionReasonException
import com.jsm.boardgame.wallet.domain.exception.InvalidWithdrawalAmountException
import com.jsm.boardgame.wallet.domain.exception.NotRequestOwnerException
import com.jsm.boardgame.wallet.domain.exception.WalletErrorCode
import com.jsm.boardgame.wallet.domain.exception.WithdrawalRequestAlreadyProcessedException
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WithdrawalRequestTest {

    private val now = Instant.parse("2026-01-01T00:00:00Z")
    private val bankAccount = BankAccount.of("국민은행", "123456789012", "홍길동")

    private fun pendingRequest(userId: Long = 1, amount: Long = 10_000): WithdrawalRequest =
        WithdrawalRequest.reconstitute(
            id = WithdrawalRequestId(1),
            userId = userId,
            amount = Money.of(amount),
            bankAccount = bankAccount,
            requestedAt = now,
            status = WithdrawalRequestStatus.PENDING,
            processedBy = null,
            processedAt = null,
            rejectionReason = null,
            version = 0,
        )

    @Test
    fun `1000원 미만이면 WITHDRAWAL_AMOUNT_INVALID`() {
        val e = assertFailsWith<InvalidWithdrawalAmountException> {
            WithdrawalRequest.request(userId = 1, amount = 900, bankAccount = bankAccount, at = now)
        }
        assertEquals(WalletErrorCode.WITHDRAWAL_AMOUNT_INVALID, e.errorCode)
    }

    @Test
    fun `100원 배수가 아니면 WITHDRAWAL_AMOUNT_INVALID`() {
        val e = assertFailsWith<InvalidWithdrawalAmountException> {
            WithdrawalRequest.request(userId = 1, amount = 1_050, bankAccount = bankAccount, at = now)
        }
        assertEquals(WalletErrorCode.WITHDRAWAL_AMOUNT_INVALID, e.errorCode)
    }

    @Test
    fun `음수 금액 요청은 AMOUNT_NEGATIVE 가 아니라 WITHDRAWAL_AMOUNT_INVALID`() {
        val e = assertFailsWith<InvalidWithdrawalAmountException> {
            WithdrawalRequest.request(userId = 1, amount = -5_000, bankAccount = bankAccount, at = now)
        }
        assertEquals(WalletErrorCode.WITHDRAWAL_AMOUNT_INVALID, e.errorCode)
    }

    @Test
    fun `상한은 없다 - 10억도 요청된다`() {
        val request = WithdrawalRequest.request(userId = 1, amount = 1_000_000_000, bankAccount = bankAccount, at = now)

        assertEquals(Money.of(1_000_000_000), request.amount)
    }

    @Test
    fun `approve 가 상태와 처리자를 채운다`() {
        val request = pendingRequest()

        request.approve(adminUserId = 99, at = now)

        assertEquals(WithdrawalRequestStatus.APPROVED, request.status)
        assertEquals(99L, request.processedBy)
        assertEquals(now, request.processedAt)
    }

    @Test
    fun `두 번 approve 하면 두 번째가 WITHDRAWAL_REQUEST_ALREADY_PROCESSED`() {
        val request = pendingRequest()
        request.approve(99, now)

        val e = assertFailsWith<WithdrawalRequestAlreadyProcessedException> {
            request.approve(99, now)
        }
        assertEquals(WalletErrorCode.WITHDRAWAL_REQUEST_ALREADY_PROCESSED, e.errorCode)
    }

    @Test
    fun `reject 후 cancel 하면 WITHDRAWAL_REQUEST_ALREADY_PROCESSED (이중 환급 방어)`() {
        val request = pendingRequest()
        request.reject(99, "사유", now)

        val e = assertFailsWith<WithdrawalRequestAlreadyProcessedException> {
            request.cancel(1, now)
        }
        assertEquals(WalletErrorCode.WITHDRAWAL_REQUEST_ALREADY_PROCESSED, e.errorCode)
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
}
