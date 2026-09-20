package com.jsm.boardgame.wallet.application.command

import com.jsm.boardgame.wallet.domain.exception.WithdrawalRequestNotFoundException
import com.jsm.boardgame.wallet.domain.model.WithdrawalRequestId
import com.jsm.boardgame.wallet.domain.repository.WithdrawalRequestRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant

/**
 * 상태만 바꾼다. 돈은 요청 시점에 이미 나갔으므로 지갑도 원장도 건드리지 않는다.
 * wallets/ledger 를 생성자에 두지 않는 이유는 실수로도 건드릴 수 없게 하기 위해서다.
 */
@Service
@Transactional
class ApproveWithdrawalRequestService(
    private val withdrawalRequests: WithdrawalRequestRepository,
    private val clock: Clock,
) : ApproveWithdrawalRequestUseCase {

    override fun approve(command: ApproveWithdrawalRequestCommand) {
        val request = withdrawalRequests.findById(WithdrawalRequestId(command.requestId))
            ?: throw WithdrawalRequestNotFoundException("승인하려는 환전 요청을 찾을 수 없음: requestId=${command.requestId}")

        request.approve(command.adminUserId, Instant.now(clock))
        withdrawalRequests.save(request)
    }
}
