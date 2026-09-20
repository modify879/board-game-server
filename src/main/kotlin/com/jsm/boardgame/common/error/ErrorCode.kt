package com.jsm.boardgame.common.error

interface ErrorCode {
    val code: String
    val kind: ErrorKind
}
