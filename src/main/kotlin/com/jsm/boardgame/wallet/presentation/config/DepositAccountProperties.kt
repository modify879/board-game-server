package com.jsm.boardgame.wallet.presentation.config

import org.springframework.boot.context.properties.ConfigurationProperties

/** 입금 계좌 안내 — 실제 계좌가 붙기 전 로컬 기본값. 도메인은 이 값을 몰라야 한다(프로필 이미지 URL 과 같은 이유). */
@ConfigurationProperties(prefix = "app.wallet.deposit-account")
data class DepositAccountProperties(
    val bankName: String,
    val accountNumber: String,
    val accountHolder: String,
)
