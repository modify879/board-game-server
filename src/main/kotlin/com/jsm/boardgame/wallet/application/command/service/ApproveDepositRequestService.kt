package com.jsm.boardgame.wallet.application.command.service

import com.jsm.boardgame.wallet.application.command.usecase.ApproveDepositRequestUseCase
import com.jsm.boardgame.wallet.application.command.usecase.ApproveDepositRequestCommand
import com.jsm.boardgame.wallet.domain.exception.DepositRequestNotFoundException
import com.jsm.boardgame.wallet.domain.model.DepositRequestId
import com.jsm.boardgame.wallet.domain.model.LedgerEntryType
import com.jsm.boardgame.wallet.domain.model.LedgerReference
import com.jsm.boardgame.wallet.domain.model.LedgerReferenceType
import com.jsm.boardgame.wallet.domain.repository.DepositRequestRepository
import com.jsm.boardgame.wallet.domain.repository.LedgerEntryRepository
import com.jsm.boardgame.wallet.domain.repository.WalletRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant

@Service
@Transactional
class ApproveDepositRequestService(
    private val depositRequests: DepositRequestRepository,
    private val wallets: WalletRepository,
    private val ledger: LedgerEntryRepository,
    private val clock: Clock,
) : ApproveDepositRequestUseCase {

    override fun approve(command: ApproveDepositRequestCommand) {
        val now = Instant.now(clock)
        val request = depositRequests.findById(DepositRequestId(command.requestId))
            ?: throw DepositRequestNotFoundException("승인하려는 충전 요청을 찾을 수 없음: requestId=${command.requestId}")

        val creditedAmount = command.creditedAmount ?: request.requestedAmount.amount

        // 1) 요청 상태를 먼저 확정하고 저장한다. 어댑터가 saveAndFlush 하므로 @Version 충돌이
        //    여기서 터지고, 동시에 들어온 두 번째 승인은 지갑을 건드리기 전에 떨어진다.
        //    지갑 반영을 먼저 하면 두 번 입금된 뒤에야 충돌을 알게 된다.
        request.approve(command.adminUserId, creditedAmount, now)
        depositRequests.save(request)

        // 2) 지갑은 없으면 여기서 만든다(findOrOpen).
        val wallet = wallets.findOrOpen(request.userId)

        val entry = wallet.record(
            type = LedgerEntryType.DEPOSIT,
            amount = request.creditedAmount!!,
            reference = LedgerReference(LedgerReferenceType.DEPOSIT_REQUEST, request.id!!.value),
            at = now,
        )
        wallets.save(wallet)
        ledger.save(entry)
    }
}
