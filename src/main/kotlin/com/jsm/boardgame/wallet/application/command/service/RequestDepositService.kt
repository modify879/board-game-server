package com.jsm.boardgame.wallet.application.command.service

import com.jsm.boardgame.wallet.application.command.usecase.RequestDepositUseCase
import com.jsm.boardgame.wallet.application.command.usecase.RequestDepositCommand
import com.jsm.boardgame.wallet.domain.model.DepositRequest
import com.jsm.boardgame.wallet.domain.model.DepositRequestId
import com.jsm.boardgame.wallet.domain.repository.DepositRequestRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant

// 지갑은 만들지 않는다 — 요청만 만든다. 돈이 실제로 움직일 때(승인 시점)에야 만들어진다.
@Service
@Transactional
class RequestDepositService(
    private val depositRequests: DepositRequestRepository,
    private val clock: Clock,
) : RequestDepositUseCase {

    override fun request(command: RequestDepositCommand): DepositRequestId {
        val request = DepositRequest.request(
            userId = command.userId,
            amount = command.amount,
            at = Instant.now(clock),
        )
        val saved = depositRequests.save(request)

        return checkNotNull(saved.id) { "save 이후에는 DepositRequest.id 가 채워져 있어야 한다" }
    }
}
