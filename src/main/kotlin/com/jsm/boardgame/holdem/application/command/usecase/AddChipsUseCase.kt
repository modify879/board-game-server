package com.jsm.boardgame.holdem.application.command.usecase

/** 호출자가 그 테이블의 실행기 스레드 위에 있어야 한다. */
interface AddChipsUseCase {
    fun addChips(command: AddChipsCommand): AddChipsResult
}

data class AddChipsCommand(val userId: Long, val amount: Long)

/** [ADDED] 는 바로 스택에 더했다는 뜻, [RESERVED] 는 핸드가 진행 중이라 끝난 뒤 적용하도록 예약했다는 뜻이다. */
enum class AddChipsResult { ADDED, RESERVED }
