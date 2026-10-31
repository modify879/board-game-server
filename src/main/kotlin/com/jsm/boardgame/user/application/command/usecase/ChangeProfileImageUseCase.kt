package com.jsm.boardgame.user.application.command.usecase

interface ChangeProfileImageUseCase {
    /** 새 프로필 이미지의 키를 돌려준다. */
    fun change(command: ChangeProfileImageCommand): String
}

class ChangeProfileImageCommand(val userId: Long, val bytes: ByteArray)
