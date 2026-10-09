package com.jsm.boardgame.wallet.application.event

/**
 * 지갑 잔액이 늘었다는 신호(충전 승인, 환전 취소·반려, 관리자 증액). 커밋될 트랜잭션 안에서 올리기만 하고,
 * 듣는 쪽이 @TransactionalEventListener(AFTER_COMMIT)로 받는다 — 롤백된 입금은 알려지지 않는다.
 * 게임에서 지갑으로 돌아오는 기립 캐시아웃은 올리지 않는다.
 */
data class WalletCredited(val userId: Long)
