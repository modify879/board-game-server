package com.jsm.boardgame.holdem.infrastructure.acl

import com.jsm.boardgame.holdem.application.command.usecase.TopUpSeatFromWalletCommand
import com.jsm.boardgame.holdem.application.command.usecase.TopUpSeatFromWalletUseCase
import com.jsm.boardgame.wallet.application.event.WalletCredited
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionalEventListener

/**
 * wallet 의 [WalletCredited] 를 커밋 후에 받아 앉아 있는 사용자의 스택에 합친다. 지갑 커밋은 이미
 * 끝났으므로 실패해도 되돌릴 것이 없다 — 로그만 남기고 삼킨다. 놓친 돈은 다음 핸드 시작
 * (HandStarter)이 합친다.
 */
@Component
class WalletCreditedListener(
    private val topUpSeatFromWallet: TopUpSeatFromWalletUseCase,
) {
    @TransactionalEventListener
    fun onWalletCredited(event: WalletCredited) {
        try {
            topUpSeatFromWallet.topUp(TopUpSeatFromWalletCommand(event.userId))
        } catch (e: Exception) {
            log.error("지갑 입금 즉시 합산에 실패했습니다(다음 핸드 시작 때 합쳐집니다): userId={}", event.userId, e)
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(WalletCreditedListener::class.java)
    }
}
