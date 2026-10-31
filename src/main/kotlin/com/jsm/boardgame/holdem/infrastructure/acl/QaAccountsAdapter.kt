package com.jsm.boardgame.holdem.infrastructure.acl

import com.jsm.boardgame.common.error.BusinessException
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

    // findMine 은 상태와 무관하게 id 내림차순이다. 반려는 페이지를 다 모은 뒤에 하므로 반려가 경계를 움직이지 않는다 —
    // 모으는 동안 끼어든 새 요청(동시 INSERT)이 경계를 밀어 같은 id 가 두 번 보일 수 있어 distinct 한다.
    override fun rejectPendingRequests(userId: Long, adminUserId: Long, reason: String) {
        rejectSkippingProcessed(
            pendingIds(deposits::findMine, userId, DepositRequestView::id, DepositRequestView::status),
        ) { rejectDeposit.reject(RejectDepositRequestCommand(it, adminUserId, reason)) }
        rejectSkippingProcessed(
            pendingIds(withdrawals::findMine, userId, WithdrawalRequestView::id, WithdrawalRequestView::status),
        ) { rejectWithdrawal.reject(RejectWithdrawalRequestCommand(it, adminUserId, reason)) }
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
        return ids.distinct()
    }

    override fun adjustBalance(userId: Long, delta: Long, adminUserId: Long, reason: String) =
        adjust.adjust(AdjustWalletBalanceCommand(userId, delta, reason, adminUserId, "qa-reset-${UUID.randomUUID()}"))

    private companion object {
        const val PAGE_SIZE = 200
    }
}

// wallet `WalletErrorCode` 의 "이미 처리됨" 코드 문자열. holdem 이 wallet `domain` 을 import 하지 않도록 문자열로만 안다.
private val ALREADY_PROCESSED_CODES = setOf("DEPOSIT_REQUEST_ALREADY_PROCESSED", "WITHDRAWAL_REQUEST_ALREADY_PROCESSED")

/**
 * 모은 뒤 반려하기 전에 다른 요청(관리자 승인 등)이 먼저 처리한 건은 건너뛴다. 예외 타입은 wallet `domain` 것이라 import 할 수 없어
 * `errorCode.code` 문자열로만 가른다. 다른 충돌(예: CONCURRENT_WALLET_UPDATE)은 요청을 대기로 남기므로 숨기지 않고 다시 던진다.
 */
internal fun rejectSkippingProcessed(ids: List<Long>, reject: (Long) -> Unit) {
    ids.forEach { id ->
        try {
            reject(id)
        } catch (e: BusinessException) {
            if (e.errorCode.code !in ALREADY_PROCESSED_CODES) throw e
        }
    }
}
