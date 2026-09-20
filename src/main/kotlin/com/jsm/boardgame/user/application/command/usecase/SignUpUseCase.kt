package com.jsm.boardgame.user.application.command.usecase

interface SignUpUseCase {
    fun signUp(command: SignUpCommand): Long
}

/** 경계를 넘는 입력은 원시 타입으로 받는다. VO 변환과 검증은 서비스가 한다. */
data class SignUpCommand(
    val username: String,
    val password: String,
    val nickname: String,
)
