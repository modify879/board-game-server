package com.jsm.boardgame.user.application.command

interface ChangeUserRoleUseCase {
    fun changeRole(command: ChangeUserRoleCommand)
}
