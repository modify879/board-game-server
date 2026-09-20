package com.jsm.boardgame.wallet.application.command.service

import com.jsm.boardgame.wallet.application.command.usecase.CancelWithdrawalRequestUseCase
import com.jsm.boardgame.wallet.application.command.usecase.CancelWithdrawalRequestCommand
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
 * 상태를 바꾸고 WITHDRAWAL_REFUND 로 환급한다. 요청 저장을 지갑 반영보다 먼저 하는 이유는
 * RejectWithdrawalRequestService 와 같다 — 동시 취소가 두 번 환급된 뒤에야 충돌을 알게 되는 것을 막는다.
 */
@Service
@Transactional
class CancelWithdrawalRequestService(
    private val withdrawalRequests: WithdrawalRequestRepository,
    private val wallets: WalletRepository,
    private val ledger: LedgerEntryRepository,
    private val clock: Clock,
) : CancelWithdrawalRequestUseCase {

    override fun cancel(command: CancelWithdrawalRequestCommand) {
        val now = Instant.now(clock)
        // 남의 요청도 "없음" 으로 응답한다 — CancelDepositRequestService 와 같은 이유(id 열거 방지).
        val request = withdrawalRequests.findById(WithdrawalRequestId(command.requestId))
            ?.takeIf { it.userId == command.requesterUserId }
            ?: throw WithdrawalRequestNotFoundException(
                "취소하려는 환전 요청이 없거나 본인 것이 아님: requestId=${command.requestId}, requesterUserId=${command.requesterUserId}",
            )

        // 소유자 검사가 상태 검사보다 먼저다(WithdrawalRequest.cancel 내부) = 이중 환급 방어
        request.cancel(command.requesterUserId, now)
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
