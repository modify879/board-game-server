package com.jsm.boardgame.holdem.application.query.service

import com.jsm.boardgame.holdem.application.config.TablePolicyProperties
import com.jsm.boardgame.holdem.application.exception.TableNotFoundException
import com.jsm.boardgame.holdem.application.port.RecentDepartures
import com.jsm.boardgame.holdem.application.port.WalletBalance
import com.jsm.boardgame.holdem.application.query.view.BuyInView
import com.jsm.boardgame.holdem.domain.model.Chips
import com.jsm.boardgame.holdem.domain.model.TablePolicy
import com.jsm.boardgame.holdem.domain.model.TableId
import com.jsm.boardgame.holdem.domain.model.UserId
import com.jsm.boardgame.holdem.domain.repository.HoldemTableRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** 착석 전에 클라이언트가 바이인 입력 범위를 그리는 조회. 범위 계산은 SeatRequester 와 같은 도메인 규칙(`buyInRange`)을 쓴다. */
@Service
@Transactional(readOnly = true)
class BuyInQueryService(
    private val tables: HoldemTableRepository,
    private val recentDepartures: RecentDepartures,
    private val walletBalance: WalletBalance,
    private val properties: TablePolicyProperties,
) {
    fun find(tableId: Long, userId: Long): BuyInView {
        val table = tables.findById(TableId(tableId))
            ?: throw TableNotFoundException("존재하지 않는 테이블입니다: tableId=$tableId")
        return viewOf(table.policy, userId)
    }

    /** 테이블을 만들기 전(생성 화면)용 — 현재 설정의 테이블 정책으로 계산한다. */
    fun findForNewTable(userId: Long): BuyInView = viewOf(properties.toPolicy(), userId)

    private fun viewOf(policy: TablePolicy, userId: Long): BuyInView {
        val obligation = recentDepartures.obligationOf(UserId(userId))
        val range = policy.buyInRange(obligation?.amount ?: Chips.ZERO)
        return BuyInView(
            minBuyIn = range.start.amount,
            maxBuyIn = range.endInclusive.amount,
            maxStack = policy.maxStack.amount,
            walletBalance = walletBalance.balanceOf(UserId(userId)),
            obligationExpiresAt = obligation?.expiresAt,
        )
    }
}
