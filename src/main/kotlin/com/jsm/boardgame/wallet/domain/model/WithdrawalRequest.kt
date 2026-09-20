package com.jsm.boardgame.wallet.domain.model

import com.jsm.boardgame.wallet.domain.exception.InvalidRejectionReasonException
import com.jsm.boardgame.wallet.domain.exception.InvalidWithdrawalAmountException
import com.jsm.boardgame.wallet.domain.exception.NotRequestOwnerException
import com.jsm.boardgame.wallet.domain.exception.WithdrawalRequestAlreadyProcessedException
import java.time.Instant

class WithdrawalRequest private constructor(
    val id: WithdrawalRequestId?,
    val userId: Long,
    val amount: Money,
    // 요청 시점의 스냅샷이 정산 근거다 — 이후 사용자가 계좌를 바꿔도 이 요청의 송금 대상은 변하지 않는다.
    val bankAccount: BankAccount,
    val requestedAt: Instant,
    status: WithdrawalRequestStatus,
    processedBy: Long?,
    processedAt: Instant?,
    rejectionReason: String?,
    val version: Long,
) {
    var status: WithdrawalRequestStatus = status
        private set

    var processedBy: Long? = processedBy
        private set

    var processedAt: Instant? = processedAt
        private set

    var rejectionReason: String? = rejectionReason
        private set

    /** 금액 인자를 받지 않는다 — 돈은 요청 시점에 이미 나갔다. */
    fun approve(adminUserId: Long, at: Instant) {
        requirePending()

        status = WithdrawalRequestStatus.APPROVED
        processedBy = adminUserId
        processedAt = at
    }

    fun reject(adminUserId: Long, reason: String, at: Instant) {
        requirePending()
        if (reason.isBlank()) {
            throw InvalidRejectionReasonException("반려 사유가 비어 있음: requestId=$id")
        }

        status = WithdrawalRequestStatus.REJECTED
        rejectionReason = reason
        processedBy = adminUserId
        processedAt = at
    }

    fun cancel(requesterUserId: Long, at: Instant) {
        // 소유자 검사를 상태 검사보다 먼저 한다 — 반대로 하면 남의 요청 처리 상태가
        // NOT_REQUEST_OWNER 대신 ALREADY_PROCESSED 로 새어 나간다.
        if (requesterUserId != userId) {
            throw NotRequestOwnerException("본인 요청이 아님: requestId=$id, owner=$userId, requester=$requesterUserId")
        }
        requirePending()

        status = WithdrawalRequestStatus.CANCELED
        processedAt = at
    }

    private fun requirePending() {
        if (status != WithdrawalRequestStatus.PENDING) {
            throw WithdrawalRequestAlreadyProcessedException("이미 처리된 요청: requestId=$id, status=$status")
        }
    }

    companion object {
        private const val MIN_AMOUNT = 1_000L

        fun request(userId: Long, amount: Long, bankAccount: BankAccount, at: Instant): WithdrawalRequest {
            if (amount < MIN_AMOUNT || amount % MONEY_UNIT != 0L) {
                throw InvalidWithdrawalAmountException("환전 요청 금액이 유효하지 않음: amount=$amount")
            }
            return WithdrawalRequest(
                id = null,
                userId = userId,
                amount = Money.of(amount),
                bankAccount = bankAccount,
                requestedAt = at,
                status = WithdrawalRequestStatus.PENDING,
                processedBy = null,
                processedAt = null,
                rejectionReason = null,
                version = 0,
            )
        }

        /** 영속 복원 전용 — 검증하지 않는다. */
        fun reconstitute(
            id: WithdrawalRequestId,
            userId: Long,
            amount: Money,
            bankAccount: BankAccount,
            requestedAt: Instant,
            status: WithdrawalRequestStatus,
            processedBy: Long?,
            processedAt: Instant?,
            rejectionReason: String?,
            version: Long,
        ): WithdrawalRequest = WithdrawalRequest(
            id = id,
            userId = userId,
            amount = amount,
            bankAccount = bankAccount,
            requestedAt = requestedAt,
            status = status,
            processedBy = processedBy,
            processedAt = processedAt,
            rejectionReason = rejectionReason,
            version = version,
        )
    }
}
