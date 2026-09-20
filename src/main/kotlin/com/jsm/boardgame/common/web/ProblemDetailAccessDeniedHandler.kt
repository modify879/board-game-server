package com.jsm.boardgame.common.web

import com.jsm.boardgame.common.error.CommonErrorCode
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.http.HttpStatus
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.web.access.AccessDeniedHandler
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

/**
 * 인증은 됐지만 권한이 없는 요청에 스프링 시큐리티가 이걸 호출한다.
 * `/api/admin` 이하가 `hasRole("ADMIN")` 을 요구하므로 일반 사용자가 관리자 API 를 부를 때마다
 * 실제로 타는 경로다. 필터 단계라 `@RestControllerAdvice` 를 거치지 않으니
 * 오류 계약(errorCode/traceId)은 여기서 직접 지킨다 — `AuthenticationEntryPoint` 와 짝이다.
 */
@Component
class ProblemDetailAccessDeniedHandler(
    private val objectMapper: ObjectMapper,
) : AccessDeniedHandler {

    override fun handle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        accessDeniedException: AccessDeniedException,
    ) {
        log.warn(
            "access denied: traceId={}, uri={}, message={}",
            MDC.get(RequestIdFilter.TRACE_ID),
            request.requestURI,
            accessDeniedException.message,
        )
        ProblemJsonWriter.write(
            objectMapper,
            request,
            response,
            HttpStatus.FORBIDDEN,
            CommonErrorCode.ACCESS_DENIED,
        )
    }

    companion object {
        private val log = LoggerFactory.getLogger(ProblemDetailAccessDeniedHandler::class.java)
    }
}
