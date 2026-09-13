package com.jsm.boardgame.user.domain.model

import com.jsm.boardgame.user.domain.exception.InvalidNicknameException
import com.jsm.boardgame.user.domain.exception.UserErrorCode
import java.text.Normalizer

@JvmInline
value class Nickname private constructor(val value: String) {

    companion object {
        private val WHITESPACE = Regex("\\s+")

        fun of(raw: String): Nickname {
            val nfc = Normalizer.normalize(raw, Normalizer.Form.NFC)
            val trimmed = nfc.trim()
            val collapsed = trimmed.replace(WHITESPACE, " ")

            if (collapsed.isEmpty()) {
                throw InvalidNicknameException(UserErrorCode.NICKNAME_BLANK, "닉네임이 비어 있습니다")
            }

            val hasForbiddenCharacter = collapsed.codePoints().toArray().any { codePoint ->
                val type = Character.getType(codePoint)
                type == Character.CONTROL.toInt() || type == Character.FORMAT.toInt()
            }
            if (hasForbiddenCharacter) {
                throw InvalidNicknameException(UserErrorCode.NICKNAME_FORBIDDEN_CHARACTER, "닉네임에 금지된 문자가 포함되어 있습니다")
            }

            val codePointCount = collapsed.codePointCount(0, collapsed.length)
            if (codePointCount !in 2..12) {
                throw InvalidNicknameException(UserErrorCode.NICKNAME_LENGTH, "닉네임 길이는 2~12자여야 합니다 (codePointCount=$codePointCount)")
            }

            return Nickname(collapsed)
        }

        /**
         * 영속 계층 복원 전용. 저장 시점에 이미 검증을 통과한 값이므로
         * 현재 규칙을 다시 적용하지 않는다. 규칙이 조여져도 기존 데이터를 읽을 수 있어야 한다.
         * 사용자 입력에는 절대 쓰지 마라 — [of] 를 써라.
         */
        fun restore(value: String): Nickname = Nickname(value)
    }
}
