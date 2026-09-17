package com.jsm.boardgame.common.support

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.MDC
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import tools.jackson.databind.ObjectMapper

/**
 * 필터 단계의 401/403 응답 작성을 한곳에 모은다 (규칙 8).
 *
 * `ProblemDetail` 을 재사용하지 않고 필드를 직접 맵으로 만들어 직렬화한다. `ProblemDetail`
 * 의 `errorCode`/`traceId` 같은 확장 프로퍼티는 `ProblemDetailJacksonMixin` 이 등록된
 * `ObjectMapper` 를 거쳐야 최상위 JSON 필드로 펼쳐지는데, 필터 단계에서 주입받는 공용
 * `ObjectMapper` 빈에 그 믹스인이 적용돼 있다고 보장할 수 없다. 맵을 직접 쓰면
 * 이 불확실성이 없다. `detail` 은 규칙 8 에 따라 아예 넣지 않는다.
 */
internal object ProblemJsonWriter {

    fun write(
        objectMapper: ObjectMapper,
        request: HttpServletRequest,
        response: HttpServletResponse,
        status: HttpStatus,
        errorCode: ErrorCode,
    ) {
        response.status = status.value()
        response.contentType = MediaType.APPLICATION_PROBLEM_JSON_VALUE

        val body = linkedMapOf<String, Any?>(
            "title" to status.reasonPhrase,
            "status" to status.value(),
            "instance" to request.requestURI,
            "errorCode" to errorCode.code,
            "traceId" to MDC.get(RequestIdFilter.TRACE_ID),
        )

        objectMapper.writeValue(response.outputStream, body)
    }
}
