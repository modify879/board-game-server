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
    }
}
