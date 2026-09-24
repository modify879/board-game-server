package com.jsm.boardgame.wallet.application.exception

import com.jsm.boardgame.common.error.BusinessException
import com.jsm.boardgame.wallet.domain.exception.WalletErrorCode

/**
 * 조정의 이중 처리 방어는 애그리거트가 아니라 응용 계층의 관심사다 — `Wallet` 은 "idempotency" 라는
 * 단어를 모른다. AdjustmentKeyRegistryAdapter 가 wallet_adjustment_keys 의 PK 위반을 이 예외로 번역한다.
 */
class AdjustmentAlreadyAppliedException(
    logMessage: String,
) : BusinessException(WalletErrorCode.ADJUSTMENT_ALREADY_APPLIED, logMessage)

/** Idempotency-Key 헤더가 없거나 비었거나 100 코드포인트를 넘음. 트림하지 않는다 — 키는 불투명한 값이다. */
class IdempotencyKeyInvalidException(
    logMessage: String,
) : BusinessException(WalletErrorCode.IDEMPOTENCY_KEY_INVALID, logMessage)
