package com.jsm.boardgame.common.web.error

import com.jsm.boardgame.common.error.CommonErrorCode
import org.springframework.core.MethodParameter
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.web.HttpRequestMethodNotSupportedException
import org.springframework.web.context.request.ServletWebRequest
import org.springframework.web.context.request.WebRequest
import org.springframework.web.bind.MissingPathVariableException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GlobalExceptionHandlerTest {

    // handleExceptionInternal 은 protected 라 직접 부르려고 연다.
    private class ExposedHandler : GlobalExceptionHandler() {
        fun internal(ex: Exception, body: Any?, status: HttpStatusCode, request: WebRequest): ResponseEntity<Any>? =
            handleExceptionInternal(ex, body, HttpHeaders(), status, request)
    }

    private val handler = ExposedHandler()
    private val servletRequest = MockHttpServletRequest("GET", "/api/x")
    private val webRequest = ServletWebRequest(servletRequest)

    @Test
    fun `예상 못 한 예외는 500 INTERNAL_ERROR 이고 메시지와 스택을 본문에 싣지 않는다`() {
        val secret = "jdbc:postgresql://db/prod password=hunter2"

        val response = handler.handleUnexpectedException(IllegalStateException(secret), servletRequest)

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.statusCode)
        val body = response.body!!
        assertEquals(CommonErrorCode.INTERNAL_ERROR.code, body.properties!!["errorCode"])
        assertEquals("/api/x", body.instance.toString())
        assertNull(body.detail)
        val rendered = body.toString()
        assertFalse(rendered.contains("hunter2"))
        assertFalse(rendered.contains("IllegalStateException"))
        assertFalse(rendered.contains("at com.jsm"))
    }

    @Test
    fun `5xx 상태의 프레임워크 예외는 INTERNAL_ERROR 코드이고 스프링 영문 detail 을 지운다`() {
        val response = handler.handleException(MissingPathVariableException("id", MethodParameter(String::class.java.getMethod("length"), -1)), webRequest)

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response?.statusCode)
        val body = assertIs<ProblemDetail>(response?.body)
        assertEquals(CommonErrorCode.INTERNAL_ERROR.code, body.properties!!["errorCode"])
        assertNull(body.detail)
    }

    @Test
    fun `4xx 프레임워크 예외는 REQUEST_INVALID 코드다`() {
        val ex = HttpRequestMethodNotSupportedException("DELETE")

        val response = handler.handleException(ex, webRequest)

        assertEquals(HttpStatus.METHOD_NOT_ALLOWED, response?.statusCode)
        val body = assertIs<ProblemDetail>(response?.body)
        assertEquals(CommonErrorCode.REQUEST_INVALID.code, body.properties!!["errorCode"])
        assertNull(body.detail)
    }

    @Test
    fun `본문이 ProblemDetail 이 아니면 그대로 돌려주고 패치하지 않는다`() {
        val response = handler.internal(RuntimeException("x"), "plain", HttpStatus.BAD_REQUEST, webRequest)

        assertEquals("plain", response?.body)
        assertEquals(HttpStatus.BAD_REQUEST, response?.statusCode)
    }

    @Test
    fun `메시지 없는 예외도 오류 없이 처리하고 본문은 비어 있다`() {
        val response = handler.internal(RuntimeException(), null, HttpStatus.INTERNAL_SERVER_ERROR, webRequest)

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response?.statusCode)
        assertNull(response?.body)
        assertTrue(response != null)
    }
}
