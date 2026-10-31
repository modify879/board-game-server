package com.jsm.boardgame.wallet.application.command.service

import com.jsm.boardgame.wallet.application.command.support.requireValidIdempotencyKey
import com.jsm.boardgame.wallet.application.command.usecase.RequestDepositUseCase
import com.jsm.boardgame.wallet.application.command.usecase.RequestDepositCommand
import com.jsm.boardgame.wallet.application.config.WalletProperties
import com.jsm.boardgame.wallet.application.exception.IdempotencyKeyReusedException
import com.jsm.boardgame.wallet.application.port.UserExistence
import com.jsm.boardgame.wallet.domain.exception.WalletOwnerNotFoundException
import com.jsm.boardgame.wallet.domain.model.DepositRequest
import com.jsm.boardgame.wallet.domain.model.UserId
import com.jsm.boardgame.wallet.domain.repository.DepositRequestRepository
import com.jsm.boardgame.wallet.domain.repository.WalletRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant

// 돈은 승인 때 움직이지만 지갑 락은 지금 잡는다 — 탈퇴(LockWalletForAccountClosureService)와 같은 락으로 직렬화해
// 탈퇴한 사용자에게 대기 요청이 남지 않게 한다. 그래서 지갑이 없으면 여기서 만들어진다.
@Service
@Transactional
class RequestDepositService(
    private val depositRequests: DepositRequestRepository,
    private val wallets: WalletRepository,
    private val userExistence: UserExistence,
    private val clock: Clock,
    private val properties: WalletProperties,
) : RequestDepositUseCase {

    override fun request(command: RequestDepositCommand): Long {
        val key = requireValidIdempotencyKey(command.idempotencyKey)
        val userId = UserId(command.userId)
        val request = DepositRequest.request(userId = userId, amount = command.amount, minAmount = properties.minDepositAmount, idempotencyKey = key, at = Instant.now(clock))

        // 같은 키의 재전송이면 원래 요청을 그대로 돌려준다. 동시 요청은 이 조회를 같이 통과하고
        // uk_deposit_requests_user_key 가 save 에서 한쪽을 막는다(IDEMPOTENCY_KEY_CONFLICT).
        depositRequests.findByUserAndIdempotencyKey(userId, key)?.let { existing ->
            if (existing.requestedAmount != request.requestedAmount) {
                throw IdempotencyKeyReusedException("같은 키로 다른 충전 요청: requestId=${existing.id}")
            }
            return checkNotNull(existing.id).value
        }

        wallets.findOrOpen(userId)
        if (!userExistence.exists(userId)) {
            throw WalletOwnerNotFoundException("탈퇴했거나 없는 사용자의 충전 요청: userId=${command.userId}")
        }

        val saved = depositRequests.save(request)

        return checkNotNull(saved.id) { "save 이후에는 DepositRequest.id 가 채워져 있어야 한다" }.value
    }
}
