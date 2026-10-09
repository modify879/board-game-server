package com.jsm.boardgame.wallet.application.command.service

import com.jsm.boardgame.wallet.application.command.usecase.RequestWithdrawalUseCase
import com.jsm.boardgame.wallet.application.command.usecase.RequestWithdrawalCommand
import com.jsm.boardgame.wallet.application.exception.IdempotencyKeyReusedException
import com.jsm.boardgame.wallet.domain.model.BankAccount
import com.jsm.boardgame.wallet.domain.model.LedgerEntryType
import com.jsm.boardgame.wallet.domain.model.LedgerReference
import com.jsm.boardgame.wallet.domain.model.LedgerReferenceType
import com.jsm.boardgame.wallet.domain.model.WithdrawalRequest
import com.jsm.boardgame.wallet.domain.model.WithdrawalRequestId
import com.jsm.boardgame.wallet.domain.model.UserId
import com.jsm.boardgame.wallet.domain.repository.LedgerEntryRepository
import com.jsm.boardgame.wallet.domain.repository.WalletRepository
import com.jsm.boardgame.wallet.domain.repository.WithdrawalRequestRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant

/**
 * 요청 시점에 즉시 차감한다. 요청만 걸어두고 승인 시점에 차감하면, 요청 후 게임에서 다 잃은 뒤
 * 승인되어 잔액이 음수가 된다.
 *
 * 잔액이 부족하면 wallet.record() 가 InsufficientBalanceException 을 던지고 @Transactional 이
 * 요청 저장까지 롤백한다 — 요청만 남고 돈은 안 빠지는 상태가 생기지 않는다.
 */
@Service
@Transactional
class RequestWithdrawalService(
    private val withdrawalRequests: WithdrawalRequestRepository,
    private val wallets: WalletRepository,
    private val ledger: LedgerEntryRepository,
    private val clock: Clock,
) : RequestWithdrawalUseCase {

    override fun request(command: RequestWithdrawalCommand): WithdrawalRequestId {
        val now = Instant.now(clock)
        val bankAccount = BankAccount.of(command.bankName, command.accountNumber, command.accountHolder)
        val key = requireValidIdempotencyKey(command.idempotencyKey)
        val userId = UserId(command.userId)
        val request = WithdrawalRequest.request(userId, command.amount, bankAccount, key, now)

        // 재전송이면 지갑을 건드리기 전에 원래 요청을 돌려준다 — 두 번 차감하지 않는다.
        // 동시 요청은 uk_withdrawal_requests_user_key 가 아래 save 에서 한쪽을 막는다(요청 → 지갑 순서 유지).
        withdrawalRequests.findByUserAndIdempotencyKey(userId, key)?.let { existing ->
            val same = existing.amount == request.amount &&
                existing.bankAccount.bankName == bankAccount.bankName &&
                existing.bankAccount.accountNumber == bankAccount.accountNumber &&
                existing.bankAccount.accountHolder == bankAccount.accountHolder
            if (!same) throw IdempotencyKeyReusedException("같은 키로 다른 환전 요청: requestId=${existing.id}")
            return checkNotNull(existing.id)
        }

        // 원장 엔트리가 요청을 가리켜야 하므로 요청을 먼저 저장해 id 를 받는다.
        val saved = withdrawalRequests.save(request)

        // 지갑이 없으면 잔액 0 인 채로 만들어지므로 아래 record 가 INSUFFICIENT_BALANCE 로 떨어진다 —
        // "지갑 없음" 을 따로 알리지 않는 것이 의도다.
        val wallet = wallets.findOrOpen(UserId(command.userId))
        val entry = wallet.record(
            type = LedgerEntryType.WITHDRAWAL_HOLD,
            amount = saved.amount,
            reference = LedgerReference(LedgerReferenceType.WITHDRAWAL_REQUEST, saved.id!!.value),
            at = now,
        )
        wallets.save(wallet)
        ledger.save(entry)

        return saved.id
    }
}
