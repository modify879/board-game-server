package com.jsm.boardgame.wallet.infrastructure.acl

import com.jsm.boardgame.user.application.query.service.UserQueryService
import com.jsm.boardgame.wallet.application.port.UserExistence
import org.springframework.stereotype.Component

@Component
class UserExistenceAdapter(
    private val userQuery: UserQueryService, // user 의 공개 application 계약. infrastructure 는 참조하지 않는다
) : UserExistence {
    override fun exists(userId: Long): Boolean = userQuery.exists(userId)
}
