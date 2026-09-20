package com.jsm.boardgame.common.error

/**
 * errorCode 는 클라이언트 계약으로 응답에 나간다.
 * logMessage 는 개발자용이며 응답에 절대 나가지 않는다.
 */
abstract class BusinessException(
    val errorCode: ErrorCode,
    val logMessage: String,
    cause: Throwable? = null,
) : RuntimeException(logMessage, cause)
