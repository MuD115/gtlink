package com.nmnd.gtlink.link

import com.nmnd.gtlink.protocol.Frame
import com.nmnd.gtlink.protocol.LinkParams
import com.nmnd.gtlink.protocol.Packet
import com.nmnd.gtlink.protocol.Tlv
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** A message the watch sent that no request was waiting for: a button press, a query. */
class Incoming(val service: Int, val command: Int, val tlv: Tlv)

/**
 * One session with the watch over an already-open byte pipe.
 *
 * Requests are matched to replies by service + command, first-come first-served, the
 * same rule Gadgetbridge's ResponseManager uses. Anything unmatched is surfaced on
 * [incoming]. The transport is injected as a write function plus [onBytes], so the
 * whole protocol can be driven by a fake watch in unit tests.
 */
class WatchLink(
    val params: LinkParams,
    private val log: ProtocolLog,
    private val write: suspend (ByteArray) -> Unit,
) {
    private class Waiter(val service: Int, val command: Int, val reply: CompletableDeferred<Tlv>)

    private val reader = Frame.Reader()
    private val waiters = mutableListOf<Waiter>()
    private val writeLock = Mutex()

    private val _incoming = MutableSharedFlow<Incoming>(extraBufferCapacity = 64)
    val incoming: SharedFlow<Incoming> = _incoming

    /** Called by the transport with whatever bytes the socket produced. */
    fun onBytes(data: ByteArray) {
        for (message in reader.feed(data)) {
            val tlv = try {
                Frame.decode(message, params)
            } catch (e: Exception) {
                log.add("! %02x/%02x undecodable: %s".format(message.service, message.command, e.message))
                continue
            } ?: continue
            log.add("<- %02x/%02x %s".format(message.service, message.command, tlv))
            val waiter = synchronized(waiters) {
                waiters.firstOrNull { it.service == message.service && it.command == message.command }
                    ?.also { waiters.remove(it) }
            }
            if (waiter != null) {
                waiter.reply.complete(tlv)
            } else {
                _incoming.tryEmit(Incoming(message.service, message.command, tlv))
            }
        }
    }

    suspend fun send(packet: Packet) {
        log.add("-> $packet")
        val frames = Frame.encode(packet, params)
        writeLock.withLock { for (f in frames) write(f) }
    }

    /** Sends an already-encoded, non-TLV body (file chunks). */
    suspend fun sendRaw(service: Int, command: Int, body: ByteArray, encrypt: Boolean) {
        val frames = Frame.encodeRaw(service, command, body, params, encrypt)
        writeLock.withLock { for (f in frames) write(f) }
    }

    /**
     * Sends [packet] and waits for the matching reply. Returns null on timeout rather
     * than throwing: many setting commands are acknowledged only by some firmwares,
     * and a missing ack must not abort the caller.
     */
    suspend fun request(packet: Packet, timeoutMs: Long = 5_000): Tlv? {
        val waiter = Waiter(packet.service, packet.command, CompletableDeferred())
        synchronized(waiters) { waiters += waiter }
        send(packet)
        val reply = withTimeoutOrNull(timeoutMs) { waiter.reply.await() }
        if (reply == null) {
            synchronized(waiters) { waiters.remove(waiter) }
            log.add("!  %02x/%02x timed out".format(packet.service, packet.command))
        }
        return reply
    }

    /** Like [request], but a timeout is a failure. Used for the handshake. */
    suspend fun requireReply(packet: Packet, timeoutMs: Long = 5_000): Tlv =
        request(packet, timeoutMs) ?: throw WatchException("Watch did not answer %02x/%02x".format(packet.service, packet.command))

    fun cancelAll() {
        synchronized(waiters) {
            waiters.forEach { it.reply.cancel() }
            waiters.clear()
        }
    }
}

class WatchException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** The watch's generic result code. 100000 (0x186A0) means success. */
const val RESULT_OK = 0x000186A0

fun Tlv.resultOk(): Boolean = asInt(0x7F, RESULT_OK) == RESULT_OK
