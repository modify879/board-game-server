package com.jsm.boardgame.holdem.application.command.usecase

interface ResetQaAccountsUseCase {
    /** 착석·대기 중이거나 없는 아이디가 하나라도 있으면 아무것도 바꾸지 않고 던진다. */
    fun reset(command: ResetQaAccountsCommand)
}

data class ResetQaAccountsCommand(val usernames: List<String>, val adminUserId: Long)
