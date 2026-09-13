package com.jsm.boardgame.user.application.command

interface SignUpUseCase {
    fun signUp(command: SignUpCommand): Long
}
