package com.jsm.boardgame.common.web.error

import com.jsm.boardgame.common.error.ErrorKind
import org.springframework.http.HttpStatus
import kotlin.test.Test
import kotlin.test.assertEquals

class ErrorKindHttpStatusTest {

    @Test
    fun `ErrorKind 는 모두 지정된 HTTP 상태로 매핑된다`() {
        val expected = mapOf(
            ErrorKind.INVALID to HttpStatus.BAD_REQUEST,
            ErrorKind.UNAUTHORIZED to HttpStatus.UNAUTHORIZED,
            ErrorKind.FORBIDDEN to HttpStatus.FORBIDDEN,
            ErrorKind.NOT_FOUND to HttpStatus.NOT_FOUND,
            ErrorKind.CONFLICT to HttpStatus.CONFLICT,
            ErrorKind.UNAVAILABLE to HttpStatus.SERVICE_UNAVAILABLE,
            ErrorKind.INTERNAL to HttpStatus.INTERNAL_SERVER_ERROR,
        )

        assertEquals(ErrorKind.entries.toSet(), expected.keys)
        expected.forEach { (kind, status) -> assertEquals(status, kind.toHttpStatus(), kind.name) }
    }
}
