package com.jsm.boardgame.user.domain.model

import com.jsm.boardgame.user.domain.exception.InvalidUsernameException

/**
 * value class 는 `init` 블록에서 원본 값을 변형해 저장할 수 없다 — `init` 은 이미 생성된
 * 프로퍼티를 검증할 뿐, 생성자에 전달되기 전에 입력을 정규화(trim/lowercase 등)하는
 * 용도로는 쓸 수 없다. 그래서 private 생성자로 막고 `of()` 팩토리에서 먼저 정규화한 뒤
 * 정규화된 값으로 인스턴스를 생성한다.
 */
@JvmInline
value class Username private constructor(val value: String) {

    companion object {
        private val PATTERN = Regex("^[a-z][a-z0-9_]{3,19}$")

        fun of(raw: String): Username {
            val normalized = raw.trim().lowercase()
            if (!PATTERN.matches(normalized)) {
                throw InvalidUsernameException("사용자명 형식이 올바르지 않습니다 (length=${raw.length})")
            }
            return Username(normalized)
        }

        /**
         * 영속 계층 복원 전용. 저장 시점에 이미 검증을 통과한 값이므로
         * 현재 규칙을 다시 적용하지 않는다. 규칙이 조여져도 기존 데이터를 읽을 수 있어야 한다.
         * 사용자 입력에는 절대 쓰지 마라 — [of] 를 써라.
         */
        fun restore(value: String): Username = Username(value)
    }
}
