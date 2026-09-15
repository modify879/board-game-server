package com.jsm.boardgame.common.support

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.http.HttpStatus
import org.springframework.security.core.AuthenticationException
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

/**
 * 인증되지 않은 요청이 인증이 필요한 경로에 닿으면 스프링 시큐리티가 이걸 호출한다.
 * 기본 구현(`Http403ForbiddenEntryPoint`/`BearerTokenAuthenticationEntryPoint`)은
 * 오류 계약을 따르지 않는 401 을 내보내므로 교체한다. `errorCode` 는 항상
 * `AUTHENTICATION_REQUIRED` 다 — 토큰이 아예 없는지, 만료됐는지, 서명이 틀렸는지,
 * 블랙리스트에 있는지는 로그로만 구분한다(응답으로 구분해줄 이유가 없다).
 */
@Component
class ProblemDetailAuthenticationEntryPoint(
    private val objectMapper: ObjectMapper,
) : AuthenticationEntryPoint {

    override fun commence(
        request: HttpServletRequest,
        response: HttpServletResponse,
        authException: AuthenticationException,
    ) {
        log.warn(
            "authentication required: traceId={}, uri={}, message={}",
            MDC.get(RequestIdFilter.TRACE_ID),
            request.requestURI,
            authException.message,
        )
        ProblemJsonWriter.write(
            objectMapper,
            request,
            response,
            HttpStatus.UNAUTHORIZED,
            CommonErrorCode.AUTHENTICATION_REQUIRED,
        )
    }

    companion object {
        private val log = LoggerFactory.getLogger(ProblemDetailAuthenticationEntryPoint::class.java)
    }
}
