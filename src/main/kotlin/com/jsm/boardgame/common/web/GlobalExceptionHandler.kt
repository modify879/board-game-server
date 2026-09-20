package com.jsm.boardgame.common.web

import com.jsm.boardgame.common.error.BusinessException
import com.jsm.boardgame.common.error.ErrorKind
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.context.request.WebRequest
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler
import java.net.URI

/** 모든 오류 응답은 RFC 9457 ProblemDetail 이며 `errorCode` 와 `traceId` 를 갖는다. 계약은 규칙 8. */
@RestControllerAdvice
class GlobalExceptionHandler : ResponseEntityExceptionHandler() {

    @ExceptionHandler(BusinessException::class)
    fun handleBusinessException(
        e: BusinessException,
        request: HttpServletRequest,
    ): ResponseEntity<ProblemDetail> {
        val status = e.errorCode.kind.toHttpStatus()
        val problemDetail = problemDetail(status, e.errorCode.code, request.requestURI)

        logByStatus(status, e.errorCode.code, e.logMessage, e)

        return ResponseEntity.status(status).body(problemDetail)
    }

    @ExceptionHandler(Exception::class)
    fun handleUnexpectedException(
        e: Exception,
        request: HttpServletRequest,
    ): ResponseEntity<ProblemDetail> {
        val status = HttpStatus.INTERNAL_SERVER_ERROR
        val problemDetail = problemDetail(status, INTERNAL_ERROR, request.requestURI)

        // 예외 메시지를 응답에 노출하지 않는다. 추적은 traceId 로 한다.
        log.error("unexpected error: traceId={}", MDC.get(RequestIdFilter.TRACE_ID), e)

        return ResponseEntity.status(status).body(problemDetail)
    }

    /**
     * 스프링 MVC 가 직접 던지는 예외(잘못된 JSON, 미지원 메서드 등)도 같은 계약을 따르게 한다.
     * 이걸 빼면 클라이언트가 분기하는 `errorCode` 가 일부 응답에만 존재하게 된다.
     */
    override fun handleExceptionInternal(
        ex: Exception,
        body: Any?,
        headers: HttpHeaders,
        statusCode: HttpStatusCode,
        request: WebRequest,
    ): ResponseEntity<Any>? {
        // 스프링은 handleHttpRequestMethodNotSupported, handleNoHandlerFoundException,
        // handleMethodArgumentNotValid 등 상당수 경로에서 이 메서드를 body=null 로 호출한다.
        // 그 경우 super 가 ErrorResponse.updateAndGetBody(...) 로 ProblemDetail 을 새로 만들어 반환하므로,
        // super 호출 전에 body 를 패치해봤자 그 결과가 버려진다.
        // 그래서 반드시 super 를 먼저 호출하고, 반환된 response 의 body(ProblemDetail 은 mutable 이므로
        // 생성 후에도 필드 수정이 반영된다)를 그 다음에 패치한다.
        // 이 순서를 뒤집지 말 것 — 뒤집으면 위 경로들에서 errorCode/traceId 가 응답에서 사라지는
        // 버그가 재현된다.
        val response = super.handleExceptionInternal(ex, body, headers, statusCode, request)
        val errorCode = if (statusCode.is5xxServerError) INTERNAL_ERROR else REQUEST_INVALID

        (response?.body as? ProblemDetail)?.let {
            // 스프링이 채운 영문 detail 을 지운다. 내용은 아래 로그에 traceId 와 함께 남는다.
            it.detail = null
            it.setProperty("errorCode", errorCode)
            it.setProperty("traceId", MDC.get(RequestIdFilter.TRACE_ID))
        }

        logByStatus(statusCode, errorCode, ex.message ?: ex.javaClass.simpleName, ex)

        return response
    }

    private fun problemDetail(status: HttpStatus, errorCode: String, requestUri: String): ProblemDetail =
        ProblemDetail.forStatus(status).apply {
            instance = URI.create(requestUri)
            setProperty("errorCode", errorCode)
            setProperty("traceId", MDC.get(RequestIdFilter.TRACE_ID))
        }

    private fun logByStatus(status: HttpStatusCode, errorCode: String, message: String, e: Exception) {
        if (status.is5xxServerError) {
            log.error("error: code={}, status={}, message={}", errorCode, status.value(), message, e)
        } else {
            log.warn("error: code={}, status={}, message={}", errorCode, status.value(), message)
        }
    }

    private fun ErrorKind.toHttpStatus(): HttpStatus = when (this) {
        ErrorKind.INVALID -> HttpStatus.BAD_REQUEST
        ErrorKind.UNAUTHORIZED -> HttpStatus.UNAUTHORIZED
        ErrorKind.CONFLICT -> HttpStatus.CONFLICT
        ErrorKind.NOT_FOUND -> HttpStatus.NOT_FOUND
        ErrorKind.FORBIDDEN -> HttpStatus.FORBIDDEN
    }

    companion object {
        private val log = LoggerFactory.getLogger(GlobalExceptionHandler::class.java)

        private const val INTERNAL_ERROR = "INTERNAL_ERROR"
        private const val REQUEST_INVALID = "REQUEST_INVALID"
    }
}
