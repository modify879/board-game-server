package com.jsm.boardgame.user.application.port

import com.jsm.boardgame.user.domain.model.UserId

/** 탈퇴 사전 조건. user 가 wallet 에 대해 아는 것은 이 Boolean 하나뿐이다. */
fun interface WalletHoldings {
    /**
     * 지갑 행을 잠그고, 잔액이 있거나 처리 대기 중인 충전·환전 요청이 있는가. 잠금은 호출자 트랜잭션이 끝날 때까지 유지된다 —
     * 트랜잭션 안에서만 부른다. 첫 지갑 생성과 엇갈리면 [com.jsm.boardgame.user.application.exception.WalletLockContendedException] —
     * 그 트랜잭션은 못 쓰니 새 트랜잭션으로 다시 부른다.
     */
    fun lockAndHasBalanceOrPendingRequests(userId: UserId): Boolean
}
