package com.jsm.boardgame.user.application.query.service

import com.jsm.boardgame.user.application.query.port.UserQueryRepository
import com.jsm.boardgame.user.application.query.view.UserProfile
import com.jsm.boardgame.user.domain.exception.UserNotFoundException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional(readOnly = true)
class UserQueryService(
    private val userQuery: UserQueryRepository,
) {
    fun findProfile(id: Long): UserProfile =
        userQuery.findProfileById(id) ?: throw UserNotFoundException("user not found: id=$id")

    /**
     * 다른 바운디드 컨텍스트가 "이 사용자 식별자가 실재하는가" 만 물을 때 쓰는 공개 계약이다.
     * `UserProfile` 모양에 호출자를 묶지 않기 위해 `findProfile` 과 별도로 둔다.
     */
    fun exists(id: Long): Boolean = userQuery.existsById(id)
}
