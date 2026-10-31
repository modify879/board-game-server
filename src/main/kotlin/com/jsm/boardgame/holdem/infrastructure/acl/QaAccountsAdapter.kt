package com.jsm.boardgame.holdem.infrastructure.acl

import com.jsm.boardgame.holdem.application.port.QaAccounts
import com.jsm.boardgame.user.application.query.service.UserQueryService
import com.jsm.boardgame.wallet.application.command.usecase.AdjustWalletBalanceCommand
import com.jsm.boardgame.wallet.application.command.usecase.AdjustWalletBalanceUseCase
import com.jsm.boardgame.wallet.application.command.usecase.RejectDepositRequestCommand
import com.jsm.boardgame.wallet.application.command.usecase.RejectDepositRequestUseCase
import com.jsm.boardgame.wallet.application.command.usecase.RejectWithdrawalRequestCommand
import com.jsm.boardgame.wallet.application.command.usecase.RejectWithdrawalRequestUseCase
import com.jsm.boardgame.wallet.application.query.service.DepositRequestQueryService
import com.jsm.boardgame.wallet.application.query.service.WithdrawalRequestQueryService
import org.springframework.context.annotation.Profile
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Component
import java.util.UUID

@Component
@Profile("qa")
class QaAccountsAdapter(
    private val users: UserQueryService,
    private val deposits: DepositRequestQueryService,
    private val withdrawals: WithdrawalRequestQueryService,
    private val rejectDeposit: RejectDepositRequestUseCase,
    private val rejectWithdrawal: RejectWithdrawalRequestUseCase,
    private val adjust: AdjustWalletBalanceUseCase,
) : QaAccounts {

    override fun userIdOf(username: String): Long? = users.findIdByUsername(username)

    // ponytail: 최근 200건만 본다 — qa 계정의 대기 요청이 그보다 많을 일은 없다.
    override fun rejectPendingRequests(userId: Long, adminUserId: Long, reason: String) {
        val page = PageRequest.of(0, 200)
        deposits.findMine(userId, page).content.filter { it.status == "PENDING" }
            .forEach { rejectDeposit.reject(RejectDepositRequestCommand(it.id, adminUserId, reason)) }
        withdrawals.findMine(userId, page).content.filter { it.status == "PENDING" }
            .forEach { rejectWithdrawal.reject(RejectWithdrawalRequestCommand(it.id, adminUserId, reason)) }
    }

    override fun adjustBalance(userId: Long, delta: Long, adminUserId: Long, reason: String) =
        adjust.adjust(AdjustWalletBalanceCommand(userId, delta, reason, adminUserId, "qa-reset-${UUID.randomUUID()}"))
}
