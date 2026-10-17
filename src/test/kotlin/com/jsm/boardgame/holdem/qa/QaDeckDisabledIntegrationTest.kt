package com.jsm.boardgame.holdem.qa

import com.jayway.jsonpath.JsonPath
import com.jsm.boardgame.TestcontainersConfiguration
import com.jsm.boardgame.UniqueNames
import com.jsm.boardgame.user.domain.model.UserId
import com.jsm.boardgame.user.domain.model.UserRole
import com.jsm.boardgame.user.domain.repository.UserRepository
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/** qa 클래스가 클래스패스에 있어도 기본 프로필에서는 @Profile("qa") 로 빈이 안 떠 API 가 없다. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class QaDeckDisabledIntegrationTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var userRepository: UserRepository

    @Test
    fun `기본 프로필에서는 ADMIN 이어도 덱 고정 API 가 404 다`() {
        val username = "u" + UniqueNames.suffix()
        val signUp = mockMvc.perform(
            post("/api/users").contentType(MediaType.APPLICATION_JSON)
                .content("""{"username":"$username","password":"password123","passwordConfirm":"password123","nickname":"n${UniqueNames.suffix()}"}"""),
        ).andExpect(status().isCreated).andReturn()
        val id = signUp.response.getHeader("Location")!!.substringAfterLast("/").toLong()
        val user = userRepository.findById(UserId(id))!!
        user.changeRole(UserRole.ADMIN)
        userRepository.save(user)
        val token: String = JsonPath.read(
            mockMvc.perform(
                post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                    .content("""{"username":"$username","password":"password123"}"""),
            ).andExpect(status().isOk).andReturn().response.contentAsString,
            "$.accessToken",
        )

        mockMvc.perform(
            post("/api/admin/qa/holdem/tables/1/next-deck").header("Authorization", "Bearer $token")
                .contentType(MediaType.APPLICATION_JSON).content("{}"),
        ).andExpect(status().isNotFound)
    }
}
