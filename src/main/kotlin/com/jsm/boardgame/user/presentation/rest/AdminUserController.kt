package com.jsm.boardgame.user.presentation.rest

import com.jsm.boardgame.user.application.command.usecase.ChangeUserRoleUseCase
import com.jsm.boardgame.user.presentation.rest.request.ChangeUserRoleRequest
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

// 인가는 SecurityConfig 의 "/api/admin/**" → hasRole("ADMIN") 이 이미 처리한다.
// 여기에 @PreAuthorize 를 덧붙이면 같은 규칙을 두 곳에서 관리하게 된다.
@RestController
@RequestMapping("/api/admin/users")
class AdminUserController(
    private val changeUserRoleUseCase: ChangeUserRoleUseCase,
) {

    @PostMapping("/{id}/role")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun changeRole(@PathVariable id: Long, @RequestBody request: ChangeUserRoleRequest) {
        changeUserRoleUseCase.changeRole(request.toCommand(id))
    }
}
