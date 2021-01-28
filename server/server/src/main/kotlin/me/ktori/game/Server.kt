package me.ktori.game

import io.ktor.application.*
import io.ktor.http.cio.websocket.*
import io.ktor.routing.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.websocket.*
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import me.ktori.game.proto.GameProto.ClMessage
import me.ktori.game.proto.GameProto.ClMessageResult
import me.ktori.game.proto.GameProto.ClSetNameResult
import me.ktori.game.proto.GameProto.SvChatMessage
import me.ktori.game.proto.GameProto.SvClientConnected
import me.ktori.game.proto.GameProto.SvClientDisconnected
import me.ktori.game.proto.GameProto.SvConnected
import me.ktori.game.proto.GameProto.SvMessage
import me.ktori.game.proto.GameProto.SvNameChanged
import org.slf4j.Logger
import org.slf4j.LoggerFactory

val logger: Logger = LoggerFactory.getLogger("Server")

class Session(val ws: WebSocketSession, val id: Int, var name: String) {
    // Функции, упрощающие создание сообщений о событиях с клиентом
    fun connected(): SvConnected = SvConnected.newBuilder().setId(id).setName(name).build()
    fun clientConnected(): SvClientConnected = SvClientConnected.newBuilder().setId(id).setName(name).build()
    fun clientDisconnected(): SvClientDisconnected = SvClientDisconnected.newBuilder().setId(id).build()
    fun nameChanged(): SvNameChanged = SvNameChanged.newBuilder().setId(id).setName(name).build()
    fun chatMessage(text: String): SvChatMessage = SvChatMessage.newBuilder().setFrom(id).setText(text).build()
}

// Упрощает отправку SvMessage в канал WebSocket
suspend fun SendChannel<Frame>.sendSvMessage(msg: SvMessage) =
    send(Frame.Binary(true, msg.toByteArray()))

suspend fun SendChannel<Frame>.sendSvMessage(msg: SvMessage.Builder) = sendSvMessage(msg.build())

val sessions = mutableSetOf<Session>()
var nextId = 1

val broadcastChannel = Channel<SvMessage.Builder>()

fun main() {
    GlobalScope.launch {
        for (msg in broadcastChannel)
            for (session in sessions)
                session.ws.outgoing.sendSvMessage(msg)
    }

    embeddedServer(Netty, port = 8080, host = "0.0.0.0") {
        install(WebSockets)
        install(Routing) {
            webSocket {
                logger.info("Got a connection")
                val id = nextId++
                val session = Session(this, id, "Client$id")

                // Отправляем клиенту уведомления о подключении всех остальных существующих клиентов
                // Чтобы он знал об их ID и именах
                for (other in sessions)
                    outgoing.sendSvMessage(SvMessage.newBuilder().setClientConnected(other.clientConnected()))

                // Отправляем клиенту его ID и имя
                outgoing.sendSvMessage(SvMessage.newBuilder().setConnected(session.connected()))

                // Уведомляем всех остальных клиентов
                broadcastChannel.send(SvMessage.newBuilder().setClientConnected(session.clientConnected()))

                sessions.add(session)

                // Читаем сообщения от клиента, пока они есть
                incoming
                    .consumeAsFlow()
                    .filterIsInstance<Frame.Binary>()
                    .map { ClMessage.parseFrom(it.data) }
                    .collect {
                        when (it.dataCase) {
                            ClMessage.DataCase.SET_NAME -> {
                                val name = it.setName.name
                                val success = if (sessions.none { s -> s.name == name }) {
                                    logger.info("${session.name} changed name to $name")
                                    session.name = name
                                    true
                                } else {
                                    logger.info("${session.name} tried to change name to $name")
                                    false
                                }

                                // Отправляем клиенту результат
                                outgoing.sendSvMessage(
                                    SvMessage.newBuilder().setResult(
                                        ClMessageResult.newBuilder().setSetName(
                                            ClSetNameResult.newBuilder().setSuccess(success)
                                        )
                                    )
                                )

                                // Уведомляем всех о новом имени
                                if (success)
                                    broadcastChannel.send(SvMessage.newBuilder().setNameChanged(session.nameChanged()))
                            }
                            ClMessage.DataCase.SEND_CHAT_MESSAGE -> {
                                logger.info("${session.name}: ${it.sendChatMessage.text}")

                                // Уведомляем всех о сообщении
                                broadcastChannel.send(
                                    SvMessage.newBuilder().setChatMessage(session.chatMessage(it.sendChatMessage.text))
                                )
                            }
                            else -> logger.warn("Unexpected data: ${it.dataCase}")
                        }
                    }

                // Сообщения от клиента кончились, удаляем его:
                sessions.remove(session)

                // Уведомляем всех остальных об отключении клиента
                broadcastChannel.send(
                    SvMessage
                        .newBuilder()
                        .setClientDisconnected(session.clientDisconnected())
                )

                logger.info("Closing connection")
            }
        }
    }.start(wait = true)
}
