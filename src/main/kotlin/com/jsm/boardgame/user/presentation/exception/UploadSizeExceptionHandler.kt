package com.jsm.boardgame.user.presentation.exception

import com.jsm.boardgame.common.web.RequestIdFilter
import com.jsm.boardgame.user.domain.exception.UserErrorCode
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.multipart.MaxUploadSizeExceededException
import java.net.URI

/**
 * 서블릿이 멀티파트를 파싱하다 `spring.servlet.multipart.max-file-size` 를 넘기면 컨트롤러에 닿기 전에
 * [MaxUploadSizeExceededException] 이 난다. 업로드는 프로필 이미지뿐이라 이를 그 오류 코드로 바꾼다.
 * 전역 핸들러보다 먼저 잡혀야 해서 우선순위를 최상위로 둔다. 응답 모양은 `GlobalExceptionHandler` 와 같다(규칙 8).
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
class UploadSizeExceptionHandler {

    @ExceptionHandler(MaxUploadSizeExceededException::class)
    fun handle(e: MaxUploadSizeExceededException, request: HttpServletRequest): ResponseEntity<ProblemDetail> {
        val code = UserErrorCode.PROFILE_IMAGE_TOO_LARGE.code
        log.warn("error: code={}, status=400, message={}", code, e.message)
        val body = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST).apply {
            instance = URI.create(request.requestURI)
            setProperty("errorCode", code)
            setProperty("traceId", MDC.get(RequestIdFilter.TRACE_ID))
        }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body)
    }

    companion object {
        private val log = LoggerFactory.getLogger(UploadSizeExceptionHandler::class.java)
    }
}
