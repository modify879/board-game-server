package com.jsm.boardgame.user.application.query.port

import com.jsm.boardgame.user.application.query.view.UserProfile
import com.jsm.boardgame.user.application.query.view.UserSummary
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable

interface UserQueryRepository {
    fun findProfileById(id: Long): UserProfile?
    /** 정확히 일치하는 아이디(이미 정규화된 값). 탈퇴한 사용자는 null. */
    fun findIdByUsername(username: String): Long?
    fun existsById(id: Long): Boolean

    fun findSummaryById(id: Long): UserSummary?

    /** [query] 가 null 이면 전체. 아이디·닉네임 부분 일치(대소문자 무시), 가입 최신순. */
    fun search(query: String?, pageable: Pageable): Page<UserSummary>
}
