package me.fabianfg.filesender.client

import com.google.gson.JsonParseException
import me.fabianfg.filesender.debug
import me.fabianfg.filesender.gson
import me.fabianfg.filesender.info
import me.fabianfg.filesender.model.Packet
import me.fabianfg.filesender.model.payloads.*
import me.fabianfg.filesender.sendPacket
import me.fabianfg.filesender.server.BasicFileDescriptor
import me.fabianfg.filesender.server.FailReason
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import java.net.URI
import java.nio.ByteBuffer
import java.nio.ByteOrder

abstract class Client(val name : String, private val clientVersion : String, uri: URI) : WebSocketClient(uri) {

    constructor(name: String, clientVersion: String, url : String) : this(name, clientVersion, URI.create(url))

    abstract fun reviewFileRequest(packet: FileShareRequestPacket)
    abstract fun onLogin(packet: AuthAcceptedPacket)
    abstract fun onLoginFailed(packet: AuthDeniedPacket)
    abstract fun onFileListUpdate(files: Map<Int, BasicFileDescriptor>)

    private val activeFileHandles = mutableMapOf<Int, FileReceiveHandle>()

    var files = mapOf<Int, BasicFileDescriptor>()
        private set

    fun downloadFile(fileId : Int) {
        sendPacket(RequestFileDownloadPacket(fileId))
    }

    fun requestFileListUpdate() {
        sendPacket(RequestFileListUpdatePacket())
    }

    override fun onOpen(handshakedata: ServerHandshake) {
        info("Opened connection to host")
        sendPacket(AuthPacket(name, clientVersion))
    }

    override fun onClose(code: Int, reason: String, remote: Boolean) {
        info("Got closed: $code $reason, by server: $remote")
    }

    override fun onMessage(message: String) {
        try {
            val packet = gson.fromJson(message, Packet::class.java)
            onPacket(packet)
        } catch (e : JsonParseException) {
            me.fabianfg.filesender.error("Failed to deserialize packet received by the server")
        }
    }

    override fun onMessage(message: ByteBuffer) {
        handleBinaryPacket(message)
    }

    override fun onError(ex: Exception) {
        ex.printStackTrace()
    }

    private fun onPacket(packet: Packet) {
        try {
            val clazz = Class.forName(packet.className)
            debug("Receive ${packet.className.substringAfterLast(".")} Json: " + packet.payload)
            when(val payload = gson.fromJson(packet.payload, clazz)!!) {
                is AuthAcceptedPacket -> authAccepted(payload)
                is AuthDeniedPacket -> authDenied(payload)
                is FileShareRequestPacket -> fileShareStart(payload)
                is FileListUpdatePacket -> fileListUpdate(payload)
            }
        } catch (e : Exception) {
            println("Invalid packet of type ${packet.className}, couldn't be deserialized")
            println("${e::class.java.simpleName}: ${e.message}")
        }
    }

    private fun fileListUpdate(packet: FileListUpdatePacket) {
        info("Received file list update: $packet")
        files = packet.files
        onFileListUpdate(packet.files)
    }

    private fun fileShareStart(packet: FileShareRequestPacket) {
        info("Received file share request: $packet")
        reviewFileRequest(packet)
    }

    fun fileShareRequestHandled(packet: FileShareRequestPacket, accept : Boolean, info: FileHandleInfo?, reason : FailReason = FailReason.CLIENT_DENIED) {
        if (accept && info != null) {
            val handle = FileReceiveHandle(this, packet.fileHandleId, packet.fileSize, packet.fileName, packet.chunkCount, packet.chunkSize, info.dir, info.onStart, info.onProgressUpdate, info.onCompleted)
            activeFileHandles[handle.fileHandleId] = handle
            sendPacket(FileShareAcceptPacket(packet.fileHandleId))
        } else {
            sendPacket(FileShareDeniedPacket(packet.fileHandleId, reason))
        }
    }

    data class FileHandleInfo(val dir : String, val onStart : (FileReceiveHandle) -> Unit, val onProgressUpdate : (FileReceiveHandle) -> Unit, val onCompleted : (FileReceiveHandle) -> Unit)

    private fun authAccepted(packet: AuthAcceptedPacket) {
        onLogin(packet)
        info("Successfully logged in to server '${packet.serverInfo.serverName}', as client ${packet.clientName} with id ${packet.receivedClientId}")
    }

    private fun authDenied(packet: AuthDeniedPacket) {
        onLoginFailed(packet)
        me.fabianfg.filesender.error("Failed to log in to server with code ${packet.code} and message '${packet.message}'. ${packet.serverInfo}")
        close()
    }

    private fun handleBinaryPacket(buffer: ByteBuffer) {
        buffer.order(ByteOrder.LITTLE_ENDIAN).position(0)
        require(buffer.limit() > 12) { "Invalid binary packet, need at least 12 bytes" }
        val fileHandleId = buffer.int
        val chunkId = buffer.long
        val handle = activeFileHandles[fileHandleId] ?: return
        handle.receivedChunk(chunkId, buffer)
    }
}