package com.jsm.boardgame.user.application.query.service

import com.jsm.boardgame.user.application.query.port.UserQueryRepository
import com.jsm.boardgame.user.application.query.view.UserProfile
import com.jsm.boardgame.user.application.query.view.UserSummary
import com.jsm.boardgame.user.domain.exception.UserErrorCode
import com.jsm.boardgame.user.domain.exception.UserNotFoundException
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private class UserQueryFakeRepository(private val profiles: Map<Long, UserProfile>) : UserQueryRepository {
    override fun findProfileById(id: Long): UserProfile? = profiles[id]
    override fun existsById(id: Long): Boolean = id in profiles

    override fun findSummaryById(id: Long): UserSummary? = null

    var lastSearchQuery: String? = "미호출"
    override fun search(query: String?, pageable: Pageable): Page<UserSummary> {
        lastSearchQuery = query
        return Page.empty(pageable)
    }
}

class UserQueryServiceTest {

    private val profile = UserProfile(1L, "alice", "앨리스", null)
    private val fake = UserQueryFakeRepository(mapOf(1L to profile))
    private val service = UserQueryService(fake)

    @Test
    fun `프로필을 돌려준다`() {
        assertEquals(profile, service.findProfile(1L))
    }

    @Test
    fun `없는 사용자의 프로필 조회는 USER_NOT_FOUND`() {
        val e = assertFailsWith<UserNotFoundException> { service.findProfile(999L) }

        assertEquals(UserErrorCode.USER_NOT_FOUND, e.errorCode)
    }

    @Test
    fun `없는 사용자의 요약 조회는 USER_NOT_FOUND`() {
        val e = assertFailsWith<UserNotFoundException> { service.findSummary(999L) }

        assertEquals(UserErrorCode.USER_NOT_FOUND, e.errorCode)
    }

    @Test
    fun `exists 는 존재 여부만 돌려준다`() {
        assertTrue(service.exists(1L))
        assertFalse(service.exists(999L))
    }

    @Test
    fun `검색어는 앞뒤 공백을 자르고 비어 있으면 null 로 넘긴다`() {
        service.search("  abc ", PageRequest.of(0, 20))
        assertEquals("abc", fake.lastSearchQuery)

        service.search("   ", PageRequest.of(0, 20))
        assertEquals(null, fake.lastSearchQuery)
    }
}
