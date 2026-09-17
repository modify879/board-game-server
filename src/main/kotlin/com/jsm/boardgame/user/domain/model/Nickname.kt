package com.jsm.boardgame.user.domain.model

import com.jsm.boardgame.user.domain.exception.InvalidNicknameException
import com.jsm.boardgame.user.domain.exception.UserErrorCode
import java.text.Normalizer

@JvmInline
value class Nickname private constructor(val value: String) {

    companion object {
        private val ASCII_SPACES = Regex(" +")

        fun of(raw: String): Nickname {
            val nfc = Normalizer.normalize(raw, Normalizer.Form.NFC)

            // 모든 공백 문자를 ASCII 공백(' ')으로 먼저 치환한 뒤 trim·축약한다.
            // Regex("\\s") 는 ASCII 전용이라 NBSP(U+00A0), 전각공백(U+3000) 같은
            // 유니코드 공백(SPACE_SEPARATOR)을 놓친다. 반면 trim() 은 Char.isWhitespace()
            // 기준으로 그런 공백을 제거하므로, 축약도 같은 기준(Char.isWhitespace())을 쓰지
            // 않으면 양끝은 지워지고 가운데만 남는 불일치가 생긴다 — 그 결과
            // "홍 길동"(ASCII 공백)과 "홍 길동"(NBSP)이 화면에서 구분되지 않는데도
            // 서로 다른 값이 되어 existsByNickname 중복 검사를 피해간다.
            // Char.isWhitespace() 는 Zs(공백 구분자) 뿐 아니라 Zl/Zp(줄·문단 구분자)와
            // 탭·개행도 공백으로 인식하는데, 이는 기존에 "\\s+" 로 탭·개행을 축약하던
            // 의도와 일치하므로 그대로 둔다.
            // 이 치환을 Regex("\\s") 기반으로 되돌리지 마라 — 위에서 설명한 재현 버그가
            // 그대로 되살아난다.
            val spacesNormalized = buildString(nfc.length) {
                for (ch in nfc) {
                    append(if (ch.isWhitespace()) ' ' else ch)
                }
            }

            val trimmed = spacesNormalized.trim()
            val collapsed = trimmed.replace(ASCII_SPACES, " ")

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

        /** 영속 복원 전용 — 검증하지 않는다. 사용자 입력에는 [of] 를 써라. */
        fun reconstitute(value: String): Nickname = Nickname(value)
    }
}
