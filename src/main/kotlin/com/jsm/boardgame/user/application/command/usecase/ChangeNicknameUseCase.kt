package com.jsm.boardgame.user.application.command.usecase

interface ChangeNicknameUseCase {
    fun changeNickname(command: ChangeNicknameCommand)
}

data class ChangeNicknameCommand(val userId: Long, val nickname: String)
