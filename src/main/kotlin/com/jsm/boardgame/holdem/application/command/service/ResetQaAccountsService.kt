package com.jsm.boardgame.holdem.application.command.service

import com.jsm.boardgame.holdem.application.command.usecase.ResetQaAccountsCommand
import com.jsm.boardgame.holdem.application.command.usecase.ResetQaAccountsUseCase
import com.jsm.boardgame.holdem.application.exception.QaUserNotFoundException
import com.jsm.boardgame.holdem.application.port.JoinQueue
import com.jsm.boardgame.holdem.application.port.QaAccounts
import com.jsm.boardgame.holdem.application.port.RecentDepartures
import com.jsm.boardgame.holdem.application.port.WalletBalance
import com.jsm.boardgame.holdem.domain.exception.AlreadySeatedException
import com.jsm.boardgame.holdem.domain.model.UserId
import com.jsm.boardgame.holdem.domain.repository.HoldemTableRepository
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Service

/**
 * QA 계정을 기준 상태(재착석 의무 없음·대기 요청 없음·잔액 [BASELINE])로 되돌린다.
 * 테이블·핸드는 건드리지 않는다 — 착석 중이면 거절한다. 검증을 전부 끝낸 뒤에야 바꾼다.
 * 각 단계가 자기 트랜잭션이라 도중 실패하면 일부만 리셋될 수 있다(qa 도구라 다시 부르면 된다).
 */
@Service
@Profile("qa")
class ResetQaAccountsService(
    private val accounts: QaAccounts,
    private val tables: HoldemTableRepository,
    private val joinQueue: JoinQueue,
    private val recentDepartures: RecentDepartures,
    private val walletBalance: WalletBalance,
) : ResetQaAccountsUseCase {

    override fun reset(command: ResetQaAccountsCommand) {
        val userIds = command.usernames.distinct().map {
            accounts.userIdOf(it) ?: throw QaUserNotFoundException("QA 리셋 불가, 없는 아이디: username=$it")
        }
        userIds.forEach {
            if (tables.findByUserId(UserId(it)) != null || joinQueue.isQueued(UserId(it))) {
                throw AlreadySeatedException("QA 리셋 불가, 착석 또는 대기 중: userId=$it")
            }
        }
        userIds.forEach { userId ->
            recentDepartures.clear(UserId(userId)) // 모든 테이블의 재착석 의무를 지운다
            accounts.rejectPendingRequests(userId, command.adminUserId, REASON)
            val delta = BASELINE - walletBalance.balanceOf(UserId(userId))
            if (delta != 0L) accounts.adjustBalance(userId, delta, command.adminUserId, REASON)
        }
    }

    companion object {
        const val REASON = "QA 리셋"

        /** scripts/qa-seed.sh 의 AMOUNT. */
        const val BASELINE = 10_000_000L
    }
}
