package com.jsm.boardgame.user.application.query

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
}
