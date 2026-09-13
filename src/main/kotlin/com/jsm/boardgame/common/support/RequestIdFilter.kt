package com.jsm.boardgame.common.support

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.MDC
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.util.UUID

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
