package com.jsm.boardgame.wallet.application.command.service

import com.jsm.boardgame.wallet.application.command.usecase.RejectDepositRequestUseCase
import com.jsm.boardgame.wallet.application.command.usecase.RejectDepositRequestCommand
import com.jsm.boardgame.wallet.domain.exception.DepositRequestNotFoundException
import com.jsm.boardgame.wallet.domain.model.DepositRequestId
import com.jsm.boardgame.wallet.domain.repository.DepositRequestRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant

// 돈은 움직이지 않는다 — 지갑·원장 출력 포트를 의존하지 않는다.
@Service
@Transactional
class RejectDepositRequestService(
    private val depositRequests: DepositRequestRepository,
    private val clock: Clock,
) : RejectDepositRequestUseCase {

    override fun reject(command: RejectDepositRequestCommand) {
        val request = depositRequests.findById(DepositRequestId(command.requestId))
            ?: throw DepositRequestNotFoundException("반려하려는 충전 요청을 찾을 수 없음: requestId=${command.requestId}")

        request.reject(command.adminUserId, command.reason, Instant.now(clock))
        depositRequests.save(request)
    }
}
