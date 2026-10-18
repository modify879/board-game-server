package com.jsm.boardgame.wallet.application.command.service

import com.jsm.boardgame.wallet.application.command.usecase.RequestDepositUseCase
import com.jsm.boardgame.wallet.application.command.usecase.RequestDepositCommand
import com.jsm.boardgame.wallet.application.config.WalletProperties
import com.jsm.boardgame.wallet.application.exception.IdempotencyKeyReusedException
import com.jsm.boardgame.wallet.domain.model.DepositRequest
import com.jsm.boardgame.wallet.domain.model.DepositRequestId
import com.jsm.boardgame.wallet.domain.model.UserId
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
    private val properties: WalletProperties,
) : RequestDepositUseCase {

    override fun request(command: RequestDepositCommand): DepositRequestId {
        val key = requireValidIdempotencyKey(command.idempotencyKey)
        val userId = UserId(command.userId)
        val request = DepositRequest.request(userId = userId, amount = command.amount, minAmount = properties.minDepositAmount, idempotencyKey = key, at = Instant.now(clock))

        // 같은 키의 재전송이면 원래 요청을 그대로 돌려준다. 동시 요청은 이 조회를 같이 통과하고
        // uk_deposit_requests_user_key 가 save 에서 한쪽을 막는다(IDEMPOTENCY_KEY_CONFLICT).
        depositRequests.findByUserAndIdempotencyKey(userId, key)?.let { existing ->
            if (existing.requestedAmount != request.requestedAmount) {
                throw IdempotencyKeyReusedException("같은 키로 다른 충전 요청: requestId=${existing.id}")
            }
            return checkNotNull(existing.id)
        }

        val saved = depositRequests.save(request)

        return checkNotNull(saved.id) { "save 이후에는 DepositRequest.id 가 채워져 있어야 한다" }
    }
}
