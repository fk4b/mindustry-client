package mindustry.client.claj

import arc.*
import arc.net.*
import arc.util.*
import mindustry.*
import mindustry.client.claj.ClajManagerDialog.*
import mindustry.game.EventType.*
import mindustry.gen.*
import mindustry.io.*
import mindustry.net.ArcNetProvider
import mindustry.net.ArcNetProvider.*
import mindustry.net.Net.*
import java.io.*
import java.nio.*
import java.util.concurrent.*

/** Implements the protocol defined in https://github.com/xzxADIxzx/Scheme-Size/blob/main/src/java/scheme/ClajIntegration.java */
object ClajSupport {
    private val clients = CopyOnWriteArrayList<Client>()
    private lateinit var dispatchListener: NetListener
    private var roomInt = 1

    fun init() {
        Events.on(MenuReturnEvent::class.java) { clear() }

        val prov = Reflect.get<NetProvider>(Vars.net, "provider").let { if (Vars.steam) Reflect.get(it, "provider") else it }
        val serv = Reflect.get<Server>(prov, "server")
        dispatchListener = Reflect.get(serv, "dispatchListener")
    }

    @Throws(IOException::class)
    @JvmStatic
    fun createRoom(ip: String, port: Int, room: Room?, code: String? = null): Client {
        val client = Client(8192, 8192, ClajSerializer())
        val roomId = roomInt++
        client.addListener(object : NetListener {
            private var clientInt = 1
            private var key: String? = null
            override fun connected(connection: Connection?) {
                if (code == null) client.sendTCP("new")
                else client.sendTCP("generate $code")
            }

            override fun disconnected(connection: Connection?, reason: DcReason?) {
                clients.remove(client)
                client.stop()
                if (room != null) Core.app.post { room.remove() }
            }

            override fun received(connection: Connection?, obj: Any?) {
                if (obj !is String) return
                if (obj.startsWith("CLaJ")) {
                    key = obj
                    if (room != null) Core.app.post { room.link = "$obj#$ip:$port" }
                } else if (obj == "new") {
                    val roomKey = key ?: return
                    try {
                        createRedirector(ip, port, roomKey, roomId, clientInt++, client)
                    } catch (e: IOException) {
                        Log.err("Could not connect the CLaJ guest", e)
                    }
                } else if (room != null) {
                    Core.app.post { if (client.isConnected) Call.sendMessage(obj) }
                }
            }
        })

        connect(client, "Claj Room $roomId", ip, port)
        return client
    }

    /** Creates a new redirector connection for the room */
    private fun createRedirector(ip: String, port: Int, key: String, roomId: Int, clientId: Int, roomClient: Client) {
        val client = Client(ArcNetProvider.clientReadBufferSize + 16_000, 16384, ClajSerializer())

        client.addListener(dispatchListener)
        client.addListener(object : NetListener {
            override fun connected(connection: Connection?) {
                if (roomClient.isConnected) client.sendTCP("host$key")
                else client.stop()
            }

            override fun disconnected(connection: Connection?, reason: DcReason?) {
                clients.remove(client)
                client.stop()
            }
        })

        connect(client, "Claj Room $roomId Client $clientId", ip, port)
    }

    private fun connect(client: Client, threadName: String, ip: String, port: Int) {
        clients.add(client)
        Threads.daemon(threadName) {
            try {
                client.run()
            } finally {
                client.dispose()
            }
        }
        try {
            client.connect(5000, ip, port, port)
        } catch (e: IOException) {
            clients.remove(client)
            client.stop()
            throw e
        }
    }

    @JvmStatic
    fun joinRoom(ip: String, port: Int, key: String, success: Runnable) {
        Vars.logic.reset()
        Vars.net.reset()

        Vars.netClient.beginConnecting()
        Log.debug("Joining room")
        val buffer = ByteBuffer.allocate(8192)
        ClajSerializer.writeCommand(buffer, "join$key")
        buffer.flip()

        Vars.net.connect(ip, port, buffer) {
            Core.app.post {
                if (Vars.net.client()) success.run()
            }
        }
    }

    fun clear() {
        val closing = clients.toList()
        clients.removeAll(closing.toSet())
        closing.forEach { it.stop() }
    }

    @Throws(IOException::class)
    @JvmStatic
    fun parseLink(link: String): Link {
        val trimmed = link.trim()

        if (!trimmed.startsWith("CLaJ")) throw IOException("@client.claj.missingprefix")
        val hash = trimmed.indexOf('#')
        if (hash != 42 + 4) throw IOException("@client.claj.wrongkeylength")
        val colon = trimmed.indexOf(':')
        if (colon == -1) throw IOException("@client.claj.colonnotfound")

        val port = Strings.parseInt(trimmed.substringAfter(':'))
        if (port !in 1..65535) throw IOException("@client.claj.portnotfound")

        return Link(trimmed.substring(0, hash), trimmed.substring(hash + 1, colon), port)
    }

    data class Link(@JvmField val key: String, @JvmField val ip: String, @JvmField val port: Int)
}


class ClajSerializer : PacketSerializer() {
    override fun write(buffer: ByteBuffer, obj: Any) {
        if (obj is String) writeCommand(buffer, obj)
        else super.write(buffer, obj)
    }

    override fun read(buffer: ByteBuffer): Any {
        if (buffer.get() == linkID) return TypeIO.readString(buffer)
        buffer.position(buffer.position() - 1)
        return super.read(buffer)
    }

    companion object {
        const val linkID: Byte = -3

        fun writeCommand(buffer: ByteBuffer, command: String) {
            buffer.put(linkID)
            TypeIO.writeString(buffer, command)
        }
    }
}
