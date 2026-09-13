package com.jsm.boardgame.common.support

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
 * 지금은 URL 별 인가 규칙에 역할 구분이 없어 인증만 되면 통과하므로 실제로는
 * 잘 타지 않는 경로지만, 오류 계약(errorCode/traceId)을 지키기 위해
 * `AuthenticationEntryPoint` 와 짝을 맞춰 둔다.
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
