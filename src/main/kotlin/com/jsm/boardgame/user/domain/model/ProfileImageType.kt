package com.jsm.boardgame.user.domain.model

import com.jsm.boardgame.user.domain.exception.UnsupportedProfileImageTypeException

/** 허용하는 프로필 이미지 형식. 클라이언트가 주장하는 Content-Type·파일명은 믿지 않고 내용(매직 바이트)으로 판별한다. */
enum class ProfileImageType(val extension: String) {
    PNG("png"),
    JPEG("jpg"),
    WEBP("webp"),
    ;

    companion object {
        private val PNG_MAGIC = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        private val JPEG_MAGIC = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
        private val RIFF_MAGIC = "RIFF".toByteArray(Charsets.US_ASCII)
        private val WEBP_MAGIC = "WEBP".toByteArray(Charsets.US_ASCII)

        fun detect(bytes: ByteArray): ProfileImageType = when {
            bytes.startsWith(PNG_MAGIC) -> PNG
            bytes.startsWith(JPEG_MAGIC) -> JPEG
            bytes.size >= 12 && bytes.startsWith(RIFF_MAGIC) && bytes.startsWith(WEBP_MAGIC, offset = 8) -> WEBP
            else -> throw UnsupportedProfileImageTypeException("지원하지 않는 이미지 형식: size=${bytes.size}")
        }

        private fun ByteArray.startsWith(prefix: ByteArray, offset: Int = 0): Boolean =
            size >= offset + prefix.size && prefix.indices.all { this[offset + it] == prefix[it] }
    }
}
