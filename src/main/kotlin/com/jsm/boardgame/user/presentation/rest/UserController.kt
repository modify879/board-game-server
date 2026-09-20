package com.jsm.boardgame.user.presentation.rest

import com.jsm.boardgame.user.application.command.usecase.SignUpUseCase
import com.jsm.boardgame.user.application.query.service.UserQueryService
import com.jsm.boardgame.user.presentation.config.ProfileImageUrlResolver
import com.jsm.boardgame.user.presentation.rest.request.SignUpRequest
import com.jsm.boardgame.user.presentation.rest.response.UserProfileResponse
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.net.URI

@RestController
@RequestMapping("/api/users")
class UserController(
    private val signUpUseCase: SignUpUseCase,
    private val userQueryService: UserQueryService,
    private val profileImageUrlResolver: ProfileImageUrlResolver,
) {

    @PostMapping
    fun signUp(@RequestBody request: SignUpRequest): ResponseEntity<Unit> {
        val id = signUpUseCase.signUp(request.toCommand())
        return ResponseEntity.created(URI.create("/api/users/$id")).build()
    }

    @GetMapping("/{id}")
    fun getProfile(@PathVariable id: Long): UserProfileResponse {
        val profile = userQueryService.findProfile(id)
        return UserProfileResponse.from(profile, profileImageUrlResolver.resolve(profile.profileImageKey))
    }
}
