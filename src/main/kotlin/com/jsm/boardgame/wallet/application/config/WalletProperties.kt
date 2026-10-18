package com.jsm.boardgame.wallet.application.config

import com.jsm.boardgame.wallet.domain.model.MONEY_UNIT
import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * 운영 정책 값. 도메인은 설정을 모르므로 서비스가 읽어 도메인 팩토리에 값으로 넘긴다.
 * 금액 단위(MONEY_UNIT)는 도메인 불변식이라 설정으로 열지 않는다 — 단위의 배수가 아닌 최소액은 기동 실패다.
 */
@ConfigurationProperties(prefix = "app.wallet")
data class WalletProperties(
    val minDepositAmount: Long,
    val minWithdrawalAmount: Long,
) {
    init {
        require(minDepositAmount > 0 && minDepositAmount % MONEY_UNIT == 0L) {
            "app.wallet.min-deposit-amount 는 ${MONEY_UNIT} 단위의 양수여야 한다: $minDepositAmount"
        }
        require(minWithdrawalAmount > 0 && minWithdrawalAmount % MONEY_UNIT == 0L) {
            "app.wallet.min-withdrawal-amount 는 ${MONEY_UNIT} 단위의 양수여야 한다: $minWithdrawalAmount"
        }
    }
}
