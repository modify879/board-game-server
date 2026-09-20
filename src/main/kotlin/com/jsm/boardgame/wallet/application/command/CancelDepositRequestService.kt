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
        // 남의 요청도 "없음" 으로 응답한다. 소유자 아님(403)과 존재하지 않음(404)을 가르면
        // 아무 id 나 넣어보는 것만으로 남의 요청이 존재하는지 열거할 수 있다.
        // 도메인의 소유자 검사(DepositRequest.cancel)는 불변식으로 그대로 남는다 — 여기서 먼저 걸릴 뿐이다.
        val request = depositRequests.findById(DepositRequestId(command.requestId))
            ?.takeIf { it.userId == command.requesterUserId }
            ?: throw DepositRequestNotFoundException(
                "취소하려는 충전 요청이 없거나 본인 것이 아님: requestId=${command.requestId}, requesterUserId=${command.requesterUserId}",
            )

        request.cancel(command.requesterUserId, Instant.now(clock))
        depositRequests.save(request)
    }
}
