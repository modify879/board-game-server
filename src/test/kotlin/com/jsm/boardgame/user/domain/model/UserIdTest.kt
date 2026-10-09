package com.jsm.boardgame.user.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals

class UserIdTest {

    @Test
    fun `문자열로 바꾸면 숫자만 나온다`() {
        assertEquals("1", "${UserId(1)}")
    }
}
