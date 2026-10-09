package com.jsm.boardgame.holdem.domain.model

/** holdem 이 바라보는 사용자(플레이어) 식별자. user 컨텍스트의 UserId 를 참조하지 않는다(규칙 1). */
@JvmInline
value class UserId(val value: Long) {
    override fun toString(): String = value.toString()
}
