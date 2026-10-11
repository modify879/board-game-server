package com.jsm.boardgame.holdem.infrastructure.acl

import com.jsm.boardgame.holdem.domain.model.UserId
import com.jsm.boardgame.holdem.application.port.WalletTransfer
import com.jsm.boardgame.holdem.domain.model.TableId
import com.jsm.boardgame.wallet.application.command.usecase.TransferToGameCommand
import com.jsm.boardgame.wallet.application.command.usecase.TransferToGameUseCase
import com.jsm.boardgame.wallet.application.command.usecase.TransferFromGameCommand
import com.jsm.boardgame.wallet.application.command.usecase.TransferFromGameUseCase
import org.springframework.stereotype.Component

@Component
class WalletTransferAdapter(
    private val transferToGame: TransferToGameUseCase,
    private val transferFromGame: TransferFromGameUseCase,
) : WalletTransfer {
    override fun toGame(userId: UserId, amount: Long, tableId: TableId, memo: String?) {
        transferToGame.transfer(TransferToGameCommand(userId.value, amount, tableId.value, memo))
    }

    override fun fromGame(userId: UserId, amount: Long, tableId: TableId, memo: String?) {
        transferFromGame.transfer(TransferFromGameCommand(userId.value, amount, tableId.value, memo))
    }
}
