package dev.kigawa.klestra

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

private const val LISTEN_PORT = 8080
private const val TICK_INTERVAL_MILLIS = 10L
private const val RETRY_DELAY_MILLIS = 1_000L
private const val CONNECT_TIMEOUT_MILLIS = 1_000
private const val OUTBOUND_QUEUE_CAPACITY = 1_024

private val json = Json { ignoreUnknownKeys = true }

/** One newline-delimited JSON line on the wire: sender node ID plus the Raft message. */
@Serializable
private data class Envelope(val from: String, val message: ConsensusMessage)

private fun log(text: String) = System.err.println("[klestra] $text")

/** Accepts "host:port", "tcp://host:port" or "host" (port defaults to [LISTEN_PORT]). */
private fun parseEndpoint(endpoint: String): Pair<String, Int> {
    val value = endpoint.substringAfter("://").substringBefore('/')
    val index = value.lastIndexOf(':')
    if (index < 0) return value to LISTEN_PORT
    return value.substring(0, index) to value.substring(index + 1).toInt()
}

/** Owns one outbound connection to a peer; reconnects forever, dropping messages while down. */
private class PeerSender(private val peerId: String, endpoint: String, private val nodeId: String) {
    private val host: String
    private val port: Int
    private val queue = LinkedBlockingQueue<String>(OUTBOUND_QUEUE_CAPACITY)

    @Volatile
    private var running = true

    init {
        val parsed = parseEndpoint(endpoint)
        host = parsed.first
        port = parsed.second
    }

    private val worker = thread(name = "klestra-send-$peerId", isDaemon = true) { loop() }

    fun send(message: ConsensusMessage) {
        // Raft tolerates message loss, so a full queue (peer down) simply drops the message.
        queue.offer(json.encodeToString(Envelope(nodeId, message)))
    }

    fun stop() {
        running = false
        worker.interrupt()
    }

    private fun loop() {
        while (running) {
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MILLIS)
                    socket.tcpNoDelay = true
                    queue.clear() // stale RPCs from the outage are useless
                    val writer = socket.getOutputStream().bufferedWriter()
                    while (running) {
                        val line = queue.poll(1, TimeUnit.SECONDS) ?: continue
                        writer.write(line)
                        writer.write("\n")
                        writer.flush()
                    }
                }
            } catch (e: InterruptedException) {
                return
            } catch (e: Exception) {
                if (!running) return
                log("peer $peerId ($host:$port) unavailable: ${e.message}; retrying")
                try {
                    Thread.sleep(RETRY_DELAY_MILLIS)
                } catch (_: InterruptedException) {
                    return
                }
            }
        }
    }
}

/** Runs one node lifetime. Returns only by throwing; the caller restarts it. */
private fun runNode(nodeId: String, configPath: String, dataDir: String) {
    val loaded = json.decodeFromString<KlestraConfig>(File(configPath).readText())
    val config = loaded.copy(spec = loaded.spec.copy(nodeId = nodeId))
    File(dataDir).mkdirs()
    log("starting node $nodeId with ${config.spec.members.size} members, dataDir=$dataDir")

    val engine = ConsensusEngine(config)
    val senders = config.spec.members.filter { it.id != nodeId }
        .associate { it.id to PeerSender(it.id, it.endpoint, nodeId) }
    val server = ServerSocket()
    val failure = LinkedBlockingQueue<Throwable>()

    // ConsensusEngine is single-threaded; every engine call uses synchronized(engine).
    fun dispatch(messages: List<OutboundMessage>) {
        messages.forEach { senders[it.to]?.send(it.message) }
    }

    fun handleConnection(socket: Socket) = socket.use {
        socket.getInputStream().bufferedReader().forEachLine { line ->
            if (line.isBlank()) return@forEachLine
            try {
                val envelope = json.decodeFromString<Envelope>(line)
                val out = synchronized(engine) { engine.receive(envelope.from, envelope.message) }
                dispatch(out)
            } catch (e: Exception) {
                log("dropping bad message: ${e.message}")
            }
        }
    }

    try {
        server.reuseAddress = true
        server.bind(InetSocketAddress(LISTEN_PORT)) // wildcard address (InetAddress.ANY)
        log("listening on 0.0.0.0:$LISTEN_PORT")
        thread(name = "klestra-accept", isDaemon = true) {
            try {
                while (!server.isClosed) {
                    val socket = server.accept()
                    thread(name = "klestra-recv", isDaemon = true) {
                        try {
                            handleConnection(socket)
                        } catch (e: Exception) {
                            log("connection closed: ${e.message}")
                        }
                    }
                }
            } catch (e: Throwable) {
                if (!server.isClosed) failure.offer(e)
            }
        }

        var last = System.nanoTime()
        while (true) {
            failure.poll()?.let { throw it }
            Thread.sleep(TICK_INTERVAL_MILLIS)
            val now = System.nanoTime()
            val elapsed = (now - last) / 1_000_000
            last = now
            dispatch(synchronized(engine) { engine.tick(elapsed) })
        }
    } finally {
        senders.values.forEach { it.stop() }
        runCatching { server.close() }
    }
}

/** Entry point: dev.kigawa.klestra.K8sMainKt. Takes args to avoid clashing with Main.kt's main(). */
fun main(args: Array<String>) {
    val nodeId = System.getenv("NODE_ID")?.takeIf { it.isNotBlank() }
        ?: error("NODE_ID environment variable is required")
    val configPath = System.getenv("KLESTRA_CONFIG_PATH") ?: "/etc/klestra/klestra-config.json"
    val dataDir = System.getenv("KLESTRA_DATA_DIR") ?: "/var/lib/klestra"

    while (true) {
        try {
            runNode(nodeId, configPath, dataDir)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            return
        } catch (e: Throwable) {
            log("node failed: $e; restarting in ${RETRY_DELAY_MILLIS}ms")
        }
        Thread.sleep(RETRY_DELAY_MILLIS)
    }
}
