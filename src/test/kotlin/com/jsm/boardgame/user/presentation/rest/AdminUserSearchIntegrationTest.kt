package com.jsm.boardgame.user.presentation.rest

import com.jsm.boardgame.IntegrationTest
import com.jayway.jsonpath.JsonPath
import com.jsm.boardgame.UniqueNames
import com.jsm.boardgame.user.domain.model.UserId
import com.jsm.boardgame.user.domain.model.UserRole
import com.jsm.boardgame.user.domain.repository.UserRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant

/** 관리자의 회원 목록·검색·단건 API(`GET /api/admin/users`, `GET /api/admin/users/{id}`) 통합 테스트. */
@IntegrationTest
class AdminUserSearchIntegrationTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var userRepository: UserRepository

    private val password = "password123"

    private fun signUp(username: String, nickname: String): Long {
        val result = mockMvc.perform(
            post("/api/users")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"username":"$username","password":"$password","passwordConfirm":"$password","nickname":"$nickname"}"""),
        ).andExpect(status().isCreated).andReturn()
        return result.response.getHeader("Location")!!.substringAfterLast("/").toLong()
    }

    private fun login(username: String): String {
        val result = mockMvc.perform(
            post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"username":"$username","password":"$password"}"""),
        ).andExpect(status().isOk).andReturn()
        return JsonPath.read(result.response.contentAsString, "$.accessToken")
    }

    // 승격 후에 로그인한다 — 역할 변경은 기존 액세스 토큰을 끊는다.
    private fun adminToken(): String {
        val username = "u" + UniqueNames.suffix()
        val id = signUp(username, "n" + UniqueNames.suffix())
        val user = userRepository.findById(UserId(id))!!
        user.changeRole(UserRole.ADMIN)
        userRepository.save(user)
        return login(username)
    }

    private fun search(token: String, query: String? = null, extra: String = ""): ResultActions {
        val builder = get("/api/admin/users?$extra")
        if (query != null) builder.param("query", query)
        return mockMvc.perform(builder.header("Authorization", "Bearer $token"))
    }

    // 공유 DB 라 다른 가입에 기대지 않는다 — 방금 만든 두 회원의 상대 순서만 본다. 이 둘이 가장 최신이므로
    // 동시에 다른 가입이 수십 건 끼지 않는 한 첫 페이지(50)에 있다.
    @Test
    fun `검색어 없이 조회하면 가입 최신순으로 돌려주고 페이지 메타가 있다`() {
        val token = adminToken()
        val first = signUp("u" + UniqueNames.suffix(), "n" + UniqueNames.suffix())
        val second = signUp("u" + UniqueNames.suffix(), "n" + UniqueNames.suffix())

        val body = search(token, extra = "size=50")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.content[0].role").exists())
            .andExpect(jsonPath("$.page.size").value(50))
            .andExpect(jsonPath("$.page.totalElements").exists())
            .andExpect(jsonPath("$.page.totalPages").exists())
            .andExpect(jsonPath("$.page.number").value(0))
            .andReturn().response.contentAsString
        val ids = JsonPath.read<List<Number>>(body, "$.content[*].id").map { it.toLong() }

        assertThat(ids).contains(first, second)
        assertThat(ids.indexOf(second)).isLessThan(ids.indexOf(first))
    }

    @Test
    fun `공통 검색어로 걸러도 가입 최신순이고 페이지 크기가 적용된다`() {
        val token = adminToken()
        val tag = UniqueNames.suffix()
        val first = signUp("u${tag}a", "n" + UniqueNames.suffix())
        val second = signUp("u${tag}b", "n" + UniqueNames.suffix())

        search(token, "u$tag", extra = "size=1")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.content.length()").value(1))
            .andExpect(jsonPath("$.content[0].id").value(second))
            .andExpect(jsonPath("$.content[0].role").value("USER"))
            .andExpect(jsonPath("$.content[0].locked").value(false))
            .andExpect(jsonPath("$.page.size").value(1))
            .andExpect(jsonPath("$.page.totalElements").value(2))
            .andExpect(jsonPath("$.page.totalPages").value(2))
            .andExpect(jsonPath("$.page.number").value(0))
        search(token, "u$tag", extra = "size=1&page=1")
            .andExpect(jsonPath("$.content[0].id").value(first))
    }

    @Test
    fun `아이디 부분 일치는 대소문자를 구분하지 않는다`() {
        val token = adminToken()
        val tag = UniqueNames.suffix()
        val id = signUp("u" + tag, "n" + UniqueNames.suffix())

        search(token, ("u" + tag).uppercase())
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.content.length()").value(1))
            .andExpect(jsonPath("$.content[0].id").value(id))
            .andExpect(jsonPath("$.content[0].username").value("u$tag"))
    }

    @Test
    fun `닉네임 부분 일치는 대소문자를 구분하지 않는다`() {
        val token = adminToken()
        val tag = UniqueNames.suffix()
        val id = signUp("u" + UniqueNames.suffix(), "N$tag")

        search(token, ("N" + tag).lowercase())
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.content.length()").value(1))
            .andExpect(jsonPath("$.content[0].id").value(id))
    }

    @Test
    fun `LIKE 와일드카드는 리터럴로 취급한다`() {
        val token = adminToken()
        val tag = UniqueNames.suffix()
        val underscore = signUp("x${tag}a_b", "n" + UniqueNames.suffix())
        signUp("x${tag}axb", "n" + UniqueNames.suffix())
        val percent = signUp("u" + UniqueNames.suffix(), "Q$tag%z")
        signUp("u" + UniqueNames.suffix(), "Q${tag}az")

        search(token, "${tag}a_b")
            .andExpect(jsonPath("$.content.length()").value(1))
            .andExpect(jsonPath("$.content[0].id").value(underscore))
        search(token, "$tag%z")
            .andExpect(jsonPath("$.content.length()").value(1))
            .andExpect(jsonPath("$.content[0].id").value(percent))
    }

    @Test
    fun `관리자가 아니면 403 ACCESS_DENIED 다`() {
        val username = "u" + UniqueNames.suffix()
        signUp(username, "n" + UniqueNames.suffix())

        search(login(username))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.errorCode").value("ACCESS_DENIED"))
    }

    private fun getOne(token: String, id: Long): ResultActions =
        mockMvc.perform(get("/api/admin/users/$id").header("Authorization", "Bearer $token"))

    @Test
    fun `단건 조회는 목록 항목과 같은 모양을 돌려준다`() {
        val token = adminToken()
        val username = "u" + UniqueNames.suffix()
        val id = signUp(username, "n" + UniqueNames.suffix())

        getOne(token, id)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").value(id))
            .andExpect(jsonPath("$.username").value(username))
            .andExpect(jsonPath("$.role").value("USER"))
            .andExpect(jsonPath("$.locked").value(false))
    }

    @Test
    fun `잠긴 회원은 locked 가 true 다`() {
        val token = adminToken()
        val id = signUp("u" + UniqueNames.suffix(), "n" + UniqueNames.suffix())
        val user = userRepository.findById(UserId(id))!!
        user.lock(Instant.now())
        userRepository.save(user)

        getOne(token, id)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.locked").value(true))
    }

    @Test
    fun `없는 회원 단건 조회는 404 USER_NOT_FOUND 다`() {
        getOne(adminToken(), 999_999_999L)
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.errorCode").value("USER_NOT_FOUND"))
    }

    @Test
    fun `관리자가 아니면 단건 조회는 403 ACCESS_DENIED 다`() {
        val username = "u" + UniqueNames.suffix()
        val id = signUp(username, "n" + UniqueNames.suffix())

        getOne(login(username), id)
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.errorCode").value("ACCESS_DENIED"))
    }
}
