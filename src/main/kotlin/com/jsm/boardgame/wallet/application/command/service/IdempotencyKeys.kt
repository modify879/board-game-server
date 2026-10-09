package com.jsm.boardgame.wallet.application.command.service

import com.jsm.boardgame.wallet.application.exception.IdempotencyKeyInvalidException

private const val IDEMPOTENCY_KEY_MAX_LENGTH = 100

/** 키는 불투명한 값이라 트림하지 않는다. 조정·충전·환전 서비스가 함께 쓴다. */
internal fun requireValidIdempotencyKey(key: String?): String {
    if (key.isNullOrBlank() || key.codePointCount(0, key.length) > IDEMPOTENCY_KEY_MAX_LENGTH) {
        throw IdempotencyKeyInvalidException("Idempotency-Key 가 유효하지 않음: key=$key")
    }
    return key
}
