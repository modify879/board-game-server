package com.jsm.boardgame.holdem.application.exception

import com.jsm.boardgame.common.error.BusinessException
import com.jsm.boardgame.holdem.domain.exception.HoldemErrorCode

/**
 * "진행 중인 핸드" 는 HoldemTable 애그리거트가 모르는 개념이라(핸드는 별도 애그리거트) 이 예외들은
 * application 계층에 둔다.
 */
class TableNotFoundException(logMessage: String) : BusinessException(HoldemErrorCode.TABLE_NOT_FOUND, logMessage)
class HandInProgressException(logMessage: String) : BusinessException(HoldemErrorCode.HAND_IN_PROGRESS, logMessage)
