package com.jsm.boardgame.wallet.application.command.service

import com.jsm.boardgame.wallet.application.command.usecase.RejectWithdrawalRequestUseCase
import com.jsm.boardgame.wallet.application.command.usecase.RejectWithdrawalRequestCommand
import com.jsm.boardgame.wallet.domain.exception.WithdrawalRequestNotFoundException
import com.jsm.boardgame.wallet.domain.model.LedgerEntryType
import com.jsm.boardgame.wallet.domain.model.LedgerReference
import com.jsm.boardgame.wallet.domain.model.LedgerReferenceType
import com.jsm.boardgame.wallet.domain.model.WithdrawalRequestId
import com.jsm.boardgame.wallet.domain.repository.LedgerEntryRepository
import com.jsm.boardgame.wallet.domain.repository.WalletRepository
import com.jsm.boardgame.wallet.domain.repository.WithdrawalRequestRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant

/**
 * 상태를 바꾸고 WITHDRAWAL_REFUND 로 환급한다.
 * 요청 저장을 지갑 반영보다 먼저 한다 — 뒤집으면 동시 반려가 두 번 환급된 뒤에야 충돌을 알게 된다.
 * 충전 승인(ApproveDepositRequestService)과 같은 이유다.
 */
@Service
@Transactional
class RejectWithdrawalRequestService(
    private val withdrawalRequests: WithdrawalRequestRepository,
    private val wallets: WalletRepository,
    private val ledger: LedgerEntryRepository,
    private val clock: Clock,
) : RejectWithdrawalRequestUseCase {

    override fun reject(command: RejectWithdrawalRequestCommand) {
        val now = Instant.now(clock)
        val request = withdrawalRequests.findById(WithdrawalRequestId(command.requestId))
            ?: throw WithdrawalRequestNotFoundException("반려하려는 환전 요청을 찾을 수 없음: requestId=${command.requestId}")

        // PENDING 아니면 여기서 떨어진다 = 이중 환급 방어
        request.reject(command.adminUserId, command.reason, now)
        withdrawalRequests.save(request)

        val wallet = wallets.findOrOpen(request.userId)
        val entry = wallet.record(
            type = LedgerEntryType.WITHDRAWAL_REFUND,
            amount = request.amount,
            reference = LedgerReference(LedgerReferenceType.WITHDRAWAL_REQUEST, request.id!!.value),
            at = now,
        )
        wallets.save(wallet)
        ledger.save(entry)
    }
}
