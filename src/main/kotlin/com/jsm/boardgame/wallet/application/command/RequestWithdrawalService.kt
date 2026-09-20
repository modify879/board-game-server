package com.jsm.boardgame.wallet.application.command

import com.jsm.boardgame.wallet.domain.model.BankAccount
import com.jsm.boardgame.wallet.domain.model.LedgerEntryType
import com.jsm.boardgame.wallet.domain.model.LedgerReference
import com.jsm.boardgame.wallet.domain.model.LedgerReferenceType
import com.jsm.boardgame.wallet.domain.model.Wallet
import com.jsm.boardgame.wallet.domain.model.WithdrawalRequest
import com.jsm.boardgame.wallet.domain.model.WithdrawalRequestId
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
        val request = WithdrawalRequest.request(command.userId, command.amount, bankAccount, now)

        // 원장 엔트리가 요청을 가리켜야 하므로 요청을 먼저 저장해 id 를 받는다.
        val saved = withdrawalRequests.save(request)

        // 지갑이 없으면 여기서 만든다. 잔액 0 이므로 아래 record 가 INSUFFICIENT_BALANCE 로 떨어진다 —
        // "지갑 없음" 을 따로 알리지 않는 것이 의도다.
        val wallet = wallets.findByUserId(command.userId) ?: wallets.save(Wallet.open(command.userId))
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
