package com.jsm.boardgame.user.application.port

import com.jsm.boardgame.user.domain.model.UserId

/** 탈퇴 사전 조건. user 가 wallet 에 대해 아는 것은 이 Boolean 하나뿐이다. */
fun interface WalletHoldings {
    /** 잔액이 있거나 처리 대기 중인 충전·환전 요청이 있는가. 지갑이 없으면 false. */
    fun hasBalanceOrPendingRequests(userId: UserId): Boolean
}
