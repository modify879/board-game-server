package com.jsm.boardgame.holdem.application.exception

import com.jsm.boardgame.common.error.BusinessException
import com.jsm.boardgame.holdem.domain.exception.HoldemErrorCode

class HandNotFoundException(logMessage: String) : BusinessException(HoldemErrorCode.HAND_NOT_FOUND, logMessage)
