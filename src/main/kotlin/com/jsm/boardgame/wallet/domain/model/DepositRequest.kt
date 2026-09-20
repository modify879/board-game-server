package com.jsm.boardgame.wallet.domain.model

import com.jsm.boardgame.wallet.domain.exception.DepositRequestAlreadyProcessedException
import com.jsm.boardgame.wallet.domain.exception.InvalidDepositAmountException
import com.jsm.boardgame.wallet.domain.exception.InvalidRejectionReasonException
import com.jsm.boardgame.wallet.domain.exception.NotRequestOwnerException
import java.time.Instant

@JvmInline
value class DepositRequestId(val value: Long)

enum class DepositRequestStatus { PENDING, APPROVED, REJECTED, CANCELED }

class DepositRequest private constructor(
    val id: DepositRequestId?,
    val userId: Long,
    val requestedAmount: Money,
    val requestedAt: Instant,
    status: DepositRequestStatus,
    creditedAmount: Money?,
    processedBy: Long?,
    processedAt: Instant?,
    rejectionReason: String?,
    val version: Long,
) {
    var status: DepositRequestStatus = status
        private set

    /** 관리자가 실제로 넣어준 금액. 승인 전에는 null. */
    var creditedAmount: Money? = creditedAmount
        private set

    var processedBy: Long? = processedBy
        private set

    var processedAt: Instant? = processedAt
        private set

    var rejectionReason: String? = rejectionReason
        private set

    fun approve(adminUserId: Long, creditedAmount: Long, at: Instant) {
        requirePending()

        // 최소 금액(MIN_AMOUNT)을 여기서는 적용하지 않는다 — 관리자는 실제 입금된 금액
        // (예: 900원)을 그대로 채워줄 수 있어야 한다. 단위(MONEY_UNIT)는 그대로 지킨다.
        if (creditedAmount <= 0L || creditedAmount % MONEY_UNIT != 0L) {
            throw InvalidDepositAmountException("승인 금액이 유효하지 않음: creditedAmount=$creditedAmount")
        }

        status = DepositRequestStatus.APPROVED
        this.creditedAmount = Money.of(creditedAmount)
        this.processedBy = adminUserId
        this.processedAt = at
    }

    fun reject(adminUserId: Long, reason: String, at: Instant) {
        requirePending()
        if (reason.isBlank()) {
            throw InvalidRejectionReasonException("반려 사유가 비어 있음: requestId=$id")
        }

        status = DepositRequestStatus.REJECTED
        rejectionReason = reason
        processedBy = adminUserId
        processedAt = at
    }

    fun cancel(requesterUserId: Long, at: Instant) {
        if (requesterUserId != userId) {
            throw NotRequestOwnerException("본인 요청이 아님: requestId=$id, owner=$userId, requester=$requesterUserId")
        }
        requirePending()

        status = DepositRequestStatus.CANCELED
        processedAt = at
    }

    private fun requirePending() {
        if (status != DepositRequestStatus.PENDING) {
            throw DepositRequestAlreadyProcessedException("이미 처리된 요청: requestId=$id, status=$status")
        }
    }

    companion object {
        private const val MIN_AMOUNT = 1_000L

        fun request(userId: Long, amount: Long, at: Instant): DepositRequest {
            if (amount < MIN_AMOUNT || amount % MONEY_UNIT != 0L) {
                throw InvalidDepositAmountException("충전 요청 금액이 유효하지 않음: amount=$amount")
            }
            return DepositRequest(
                id = null,
                userId = userId,
                requestedAmount = Money.of(amount),
                requestedAt = at,
                status = DepositRequestStatus.PENDING,
                creditedAmount = null,
                processedBy = null,
                processedAt = null,
                rejectionReason = null,
                version = 0,
            )
        }

        /** 영속 복원 전용 — 검증하지 않는다. */
        fun reconstitute(
            id: DepositRequestId,
            userId: Long,
            requestedAmount: Money,
            requestedAt: Instant,
            status: DepositRequestStatus,
            creditedAmount: Money?,
            processedBy: Long?,
            processedAt: Instant?,
            rejectionReason: String?,
            version: Long,
        ): DepositRequest = DepositRequest(
            id = id,
            userId = userId,
            requestedAmount = requestedAmount,
            requestedAt = requestedAt,
            status = status,
            creditedAmount = creditedAmount,
            processedBy = processedBy,
            processedAt = processedAt,
            rejectionReason = rejectionReason,
            version = version,
        )
    }
}
