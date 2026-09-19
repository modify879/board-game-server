package com.jsm.boardgame.wallet.application.command

import com.jsm.boardgame.wallet.domain.exception.DepositRequestNotFoundException
import com.jsm.boardgame.wallet.domain.model.DepositRequestId
import com.jsm.boardgame.wallet.domain.repository.DepositRequestRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant

@Service
@Transactional
class CancelDepositRequestService(
    private val depositRequests: DepositRequestRepository,
    private val clock: Clock,
) : CancelDepositRequestUseCase {

    override fun cancel(command: CancelDepositRequestCommand) {
        val request = depositRequests.findById(DepositRequestId(command.requestId))
            ?: throw DepositRequestNotFoundException("취소하려는 충전 요청을 찾을 수 없음: requestId=${command.requestId}")

        request.cancel(command.requesterUserId, Instant.now(clock))
        depositRequests.save(request)
    }
}
