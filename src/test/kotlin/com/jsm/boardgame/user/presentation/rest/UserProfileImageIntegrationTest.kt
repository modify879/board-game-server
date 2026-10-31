package com.jsm.boardgame.user.presentation.rest

import com.jayway.jsonpath.JsonPath
import com.jsm.boardgame.IntegrationTest
import com.jsm.boardgame.UniqueNames
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.net.URI

@IntegrationTest
class UserProfileImageIntegrationTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3, 4)

    private class Account(val id: Long, val accessToken: String)

    private fun signUpAndLogin(): Account {
        val username = "u" + UniqueNames.suffix()
        val signUp = mockMvc.perform(
            post("/api/users").contentType(MediaType.APPLICATION_JSON)
                .content("""{"username":"$username","password":"password123","passwordConfirm":"password123","nickname":"n${UniqueNames.suffix()}"}"""),
        ).andExpect(status().isCreated).andReturn()
        val id = signUp.response.getHeader("Location")!!.substringAfterLast("/").toLong()
        val login = mockMvc.perform(
            post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("""{"username":"$username","password":"password123"}"""),
        ).andExpect(status().isOk).andReturn()
        return Account(id, JsonPath.read(login.response.contentAsString, "$.accessToken"))
    }

    private fun upload(accessToken: String?, content: ByteArray, filename: String = "me.png"): ResultActions =
        mockMvc.perform(
            multipart(HttpMethod.PUT, "/api/users/me/profile-image")
                .file(MockMultipartFile("file", filename, "image/png", content))
                .apply { if (accessToken != null) header(HttpHeaders.AUTHORIZATION, "Bearer $accessToken") },
        )

    private fun profileImageUrl(account: Account): String =
        JsonPath.read(
            mockMvc.perform(get("/api/users/${account.id}").header(HttpHeaders.AUTHORIZATION, "Bearer ${account.accessToken}"))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            "$.profileImageUrl",
        )

    @Test
    fun `PNG 를 올리면 200 과 URL 이 오고 프로필에 반영되며 인증 없이 이미지를 받을 수 있다`() {
        val account = signUpAndLogin()

        val result = upload(account.accessToken, png).andExpect(status().isOk).andReturn()
        val url = JsonPath.read<String>(result.response.contentAsString, "$.profileImageUrl")

        assertThat(url).matches("/images/profiles/${account.id}/[0-9a-f-]{36}\\.png")
        assertThat(profileImageUrl(account)).isEqualTo(url)

        val served = mockMvc.perform(get(URI.create(url).path))
            .andExpect(status().isOk)
            .andExpect(header().string(HttpHeaders.CONTENT_TYPE, MediaType.IMAGE_PNG_VALUE))
            .andExpect(header().string("X-Content-Type-Options", "nosniff"))
            .andReturn()
        assertThat(served.response.contentAsByteArray).isEqualTo(png)
    }

    @Test
    fun `이미지를 다시 올리면 옛 파일이 지워진다`() {
        val account = signUpAndLogin()
        val first = JsonPath.read<String>(upload(account.accessToken, png).andReturn().response.contentAsString, "$.profileImageUrl")
        val second = JsonPath.read<String>(upload(account.accessToken, png).andReturn().response.contentAsString, "$.profileImageUrl")

        assertThat(second).isNotEqualTo(first)
        mockMvc.perform(get(URI.create(first).path)).andExpect(status().isNotFound)
        mockMvc.perform(get(URI.create(second).path)).andExpect(status().isOk)
    }

    @Test
    fun `확장자만 png 인 텍스트 파일은 400 PROFILE_IMAGE_UNSUPPORTED_TYPE 이다`() {
        val account = signUpAndLogin()

        upload(account.accessToken, "not an image".toByteArray())
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errorCode").value("PROFILE_IMAGE_UNSUPPORTED_TYPE"))
    }

    @Test
    fun `빈 파일은 400 PROFILE_IMAGE_UNSUPPORTED_TYPE 이다`() {
        val account = signUpAndLogin()

        upload(account.accessToken, ByteArray(0))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errorCode").value("PROFILE_IMAGE_UNSUPPORTED_TYPE"))
    }

    @Test
    fun `2MB 를 넘으면 400 PROFILE_IMAGE_TOO_LARGE 다`() {
        val account = signUpAndLogin()

        upload(account.accessToken, png + ByteArray(2 * 1024 * 1024))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errorCode").value("PROFILE_IMAGE_TOO_LARGE"))
    }

    @Test
    fun `토큰 없이 올리면 401 이다`() {
        upload(null, png).andExpect(status().isUnauthorized)
    }

    @Test
    fun `삭제하면 204 이고 프로필이 기본 이미지로 돌아간다`() {
        val account = signUpAndLogin()
        val defaultUrl = profileImageUrl(account)
        upload(account.accessToken, png).andExpect(status().isOk)

        mockMvc.perform(delete("/api/users/me/profile-image").header(HttpHeaders.AUTHORIZATION, "Bearer ${account.accessToken}"))
            .andExpect(status().isNoContent)

        assertThat(profileImageUrl(account)).isEqualTo(defaultUrl)
    }
}
