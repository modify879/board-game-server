package com.jsm.boardgame.user.presentation.exception

import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.web.multipart.MaxUploadSizeExceededException
import kotlin.test.Test
import kotlin.test.assertEquals

class UploadSizeExceptionHandlerTest {

    @Test
    fun `멀티파트 크기 초과는 400 과 PROFILE_IMAGE_TOO_LARGE 가 된다`() {
        val response = UploadSizeExceptionHandler()
            .handle(MaxUploadSizeExceededException(2 * 1024 * 1024), MockHttpServletRequest("PUT", "/api/users/me/profile-image"))

        assertEquals(400, response.statusCode.value())
        assertEquals("PROFILE_IMAGE_TOO_LARGE", response.body!!.properties!!["errorCode"])
    }
}
