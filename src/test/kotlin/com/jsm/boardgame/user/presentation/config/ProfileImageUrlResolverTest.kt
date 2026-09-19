package com.jsm.boardgame.user.presentation.config

import kotlin.test.Test
import kotlin.test.assertEquals

class ProfileImageUrlResolverTest {

    private fun resolverWith(baseUrl: String) =
        ProfileImageUrlResolver(
            ProfileImageProperties(
                baseUrl = baseUrl,
                defaultUrl = "https://cdn.example.com/default.png",
            ),
        )

    @Test
    fun `key 가 null 이면 defaultUrl 을 그대로 반환한다`() {
        val resolver = resolverWith("https://cdn.example.com/images")

        assertEquals("https://cdn.example.com/default.png", resolver.resolve(null))
    }

    @Test
    fun `key 가 있으면 baseUrl 과 이어붙인 URL 을 반환한다`() {
        val resolver = resolverWith("https://cdn.example.com/images")

        assertEquals("https://cdn.example.com/images/abc.png", resolver.resolve("abc.png"))
    }

    @Test
    fun `baseUrl 끝과 key 앞에 슬래시가 겹쳐도 중복되지 않는다`() {
        val resolver = resolverWith("https://cdn.example.com/images/")

        assertEquals("https://cdn.example.com/images/abc.png", resolver.resolve("/abc.png"))
    }

    @Test
    fun `baseUrl 에 끝 슬래시가 없고 key 에 앞 슬래시가 있어도 중복되지 않는다`() {
        val resolver = resolverWith("https://cdn.example.com/images")

        assertEquals("https://cdn.example.com/images/abc.png", resolver.resolve("/abc.png"))
    }

    @Test
    fun `baseUrl 끝에 슬래시가 없고 key 에도 앞 슬래시가 없으면 슬래시를 하나 넣는다`() {
        val resolver = resolverWith("https://cdn.example.com/images")

        assertEquals("https://cdn.example.com/images/nested/abc.png", resolver.resolve("nested/abc.png"))
    }
}
