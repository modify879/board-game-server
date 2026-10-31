package com.jsm.boardgame.common.web.error

import com.jsm.boardgame.common.error.ErrorKind
import com.jsm.boardgame.common.web.filter.RequestIdFilter
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.http.ProblemDetail
import java.net.URI

private val logger = LoggerFactory.getLogger("com.jsm.boardgame.common.web.error.GlobalExceptionHandler")

/** `@RestControllerAdvice` 들이 공유하는 응답·로그 조립(규칙 8). `ErrorKind` → 상태 매핑은 여기 하나다. */
internal fun ErrorKind.toHttpStatus(): HttpStatus = when (this) {
    ErrorKind.INVALID -> HttpStatus.BAD_REQUEST
    ErrorKind.UNAUTHORIZED -> HttpStatus.UNAUTHORIZED
    ErrorKind.CONFLICT -> HttpStatus.CONFLICT
    ErrorKind.NOT_FOUND -> HttpStatus.NOT_FOUND
    ErrorKind.FORBIDDEN -> HttpStatus.FORBIDDEN
    ErrorKind.UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE
    ErrorKind.INTERNAL -> HttpStatus.INTERNAL_SERVER_ERROR
}

internal fun problemDetail(status: HttpStatus, errorCode: String, requestUri: String): ProblemDetail =
    ProblemDetail.forStatus(status).apply {
        instance = URI.create(requestUri)
        setProperty("errorCode", errorCode)
        setProperty("traceId", MDC.get(RequestIdFilter.TRACE_ID))
    }

/** 4xx 는 WARN 스택 없이, 5xx 는 ERROR 스택 포함. */
internal fun logByStatus(status: HttpStatusCode, errorCode: String, message: String, e: Exception) {
    if (status.is5xxServerError) {
        logger.error("error: code={}, status={}, message={}", errorCode, status.value(), message, e)
    } else {
        logger.warn("error: code={}, status={}, message={}", errorCode, status.value(), message)
    }
}
