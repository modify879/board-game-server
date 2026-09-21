package com.jsm.boardgame.holdem.application.exception

import com.jsm.boardgame.common.error.BusinessException
import com.jsm.boardgame.holdem.domain.exception.HoldemErrorCode

class UnknownActionException(logMessage: String) : BusinessException(HoldemErrorCode.UNKNOWN_ACTION, logMessage)
