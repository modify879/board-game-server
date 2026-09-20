package com.jsm.boardgame.user.application.command.usecase

interface ChangeUserRoleUseCase {
    fun changeRole(command: ChangeUserRoleCommand)
}

/** 경계를 넘는 입력은 원시 타입으로 받는다. UserRole 변환은 서비스가 한다. */
data class ChangeUserRoleCommand(val targetUserId: Long, val role: String)
