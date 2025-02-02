package io.drake.sfa.utils

import go.Seq
import io.drake.libbox.CommandClient
import io.drake.libbox.CommandClientHandler
import io.drake.libbox.CommandClientOptions
import io.drake.libbox.Libbox
import io.drake.libbox.OutboundGroup
import io.drake.libbox.OutboundGroupIterator
import io.drake.libbox.StatusMessage
import io.drake.libbox.StringIterator
import io.drake.sfa.ktx.toList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

open class CommandClient(
    private val scope: CoroutineScope,
    private val connectionType: ConnectionType,
    private val handler: Handler
) {

    enum class ConnectionType {
        Status, Groups, Log, ClashMode
    }

    interface Handler {

        fun onConnected() {}
        fun onDisconnected() {}
        fun updateStatus(status: StatusMessage) {}
        fun updateGroups(groups: List<OutboundGroup>) {}
        fun clearLog() {}
        fun appendLog(message: String) {}
        fun initializeClashMode(modeList: List<String>, currentMode: String) {}
        fun updateClashMode(newMode: String) {}

    }


    private var commandClient: CommandClient? = null
    private val clientHandler = ClientHandler()
    fun connect() {
        disconnect()
        val options = CommandClientOptions()
        options.command = when (connectionType) {
            ConnectionType.Status -> Libbox.CommandStatus
            ConnectionType.Groups -> Libbox.CommandGroup
            ConnectionType.Log -> Libbox.CommandLog
            ConnectionType.ClashMode -> Libbox.CommandClashMode
        }
        options.statusInterval = 2 * 1000 * 1000 * 1000
        val commandClient = CommandClient(clientHandler, options)
        scope.launch(Dispatchers.IO) {
            for (i in 1..10) {
                delay(100 + i.toLong() * 50)
                try {
                    commandClient.connect()
                } catch (ignored: Exception) {
                    continue
                }
                if (!isActive) {
                    runCatching {
                        commandClient.disconnect()
                    }
                    return@launch
                }
                this@CommandClient.commandClient = commandClient
                return@launch
            }
            runCatching {
                commandClient.disconnect()
            }
        }
    }

    fun disconnect() {
        commandClient?.apply {
            runCatching {
                disconnect()
            }
            Seq.destroyRef(refnum)
        }
        commandClient = null
    }

    private inner class ClientHandler : CommandClientHandler {

        override fun connected() {
            handler.onConnected()
        }

        override fun disconnected(message: String?) {
            handler.onDisconnected()
        }

        override fun writeGroups(message: OutboundGroupIterator?) {
            if (message == null) {
                return
            }
            val groups = mutableListOf<OutboundGroup>()
            while (message.hasNext()) {
                groups.add(message.next())
            }
            handler.updateGroups(groups)
        }

        override fun clearLog() {
           handler.clearLog()
        }

        override fun writeLog(message: String?) {
            if (message == null) {
                return
            }
            handler.appendLog(message)
        }

        override fun writeStatus(message: StatusMessage?) {
            if (message == null) {
                return
            }
            handler.updateStatus(message)
        }

        override fun initializeClashMode(modeList: StringIterator, currentMode: String) {
            handler.initializeClashMode(modeList.toList(), currentMode)
        }

        override fun updateClashMode(newMode: String) {
            handler.updateClashMode(newMode)
        }

    }

}