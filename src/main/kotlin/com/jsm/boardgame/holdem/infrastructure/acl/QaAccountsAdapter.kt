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
import com.jsm.boardgame.wallet.application.query.view.DepositRequestView
import com.jsm.boardgame.wallet.application.query.view.WithdrawalRequestView
import org.springframework.context.annotation.Profile
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable
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

    // findMine 은 상태와 무관하게 id 내림차순이라 반려해도 페이지 경계가 움직이지 않는다 — 끝까지 넘기며 대기 건을 모은다.
    override fun rejectPendingRequests(userId: Long, adminUserId: Long, reason: String) {
        pendingIds(deposits::findMine, userId, DepositRequestView::id, DepositRequestView::status)
            .forEach { rejectDeposit.reject(RejectDepositRequestCommand(it, adminUserId, reason)) }
        pendingIds(withdrawals::findMine, userId, WithdrawalRequestView::id, WithdrawalRequestView::status)
            .forEach { rejectWithdrawal.reject(RejectWithdrawalRequestCommand(it, adminUserId, reason)) }
    }

    private fun <T : Any> pendingIds(
        find: (Long, Pageable) -> Page<T>,
        userId: Long,
        id: (T) -> Long,
        status: (T) -> String,
    ): List<Long> {
        val ids = mutableListOf<Long>()
        var pageable: Pageable = PageRequest.of(0, PAGE_SIZE)
        do {
            val page = find(userId, pageable)
            page.content.filter { status(it) == "PENDING" }.mapTo(ids, id)
            pageable = page.nextPageable()
        } while (page.hasNext())
        return ids
    }

    override fun adjustBalance(userId: Long, delta: Long, adminUserId: Long, reason: String) =
        adjust.adjust(AdjustWalletBalanceCommand(userId, delta, reason, adminUserId, "qa-reset-${UUID.randomUUID()}"))

    private companion object {
        const val PAGE_SIZE = 200
    }
}
