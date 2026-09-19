package com.jsm.boardgame.wallet.application.query

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional(readOnly = true)
class WalletQueryService(
    private val walletQuery: WalletQueryRepository,
) {

    // 지갑이 없으면 0을 돌려주고 만들지 않는다 — 조회가 상태를 바꾸면 규칙 3(명령/조회 분리)이 깨진다.
    // 지갑은 첫 입금 승인·환전 요청·관리자 조정처럼 실제로 돈이 움직이는 명령 경로에서만 생긴다.
    fun findBalance(userId: Long): WalletBalanceView =
        walletQuery.findBalanceByUserId(userId) ?: WalletBalanceView(0)

    fun findLedger(userId: Long, pageable: Pageable): Page<LedgerEntryView> {
        val walletId = walletQuery.findWalletIdByUserId(userId) ?: return Page.empty(pageable)
        return walletQuery.findLedgerByWalletId(walletId, pageable)
    }
}
