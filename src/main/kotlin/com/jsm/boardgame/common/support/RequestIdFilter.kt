package com.jsm.boardgame.common.support

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.MDC
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.util.UUID

/**
 * `@Order` 가 없으면 스프링 부트가 이 필터에 `Ordered.LOWEST_PRECEDENCE` 를 매겨
 * 스프링 시큐리티의 필터 체인보다 뒤에 놓는다. 그러면 인증/인가 실패로 시큐리티가
 * 응답을 끝내버리는 요청은 이 필터를 아예 통과하지 못해 MDC 에 traceId 가 없고,
 * `ProblemDetailAuthenticationEntryPoint`/`ProblemDetailAccessDeniedHandler` 가
 * traceId 없는 응답을 내보내게 된다. 가장 먼저 실행되도록 강제한다.
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@Component
class RequestIdFilter : OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val traceId = UUID.randomUUID().toString().take(8)
        try {
            MDC.put(TRACE_ID, traceId)
            response.setHeader("X-Trace-Id", traceId)
            filterChain.doFilter(request, response)
        } finally {
            MDC.remove(TRACE_ID)
        }
    }

    companion object {
        const val TRACE_ID = "traceId"
    }
}
