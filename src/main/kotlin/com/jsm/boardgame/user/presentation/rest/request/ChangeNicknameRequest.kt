package com.jsm.boardgame.user.presentation.rest.request

import com.jsm.boardgame.user.application.command.usecase.ChangeNicknameCommand

data class ChangeNicknameRequest(val nickname: String) {
    fun toCommand(userId: Long): ChangeNicknameCommand = ChangeNicknameCommand(userId, nickname)
}
