package local.ringleader.core

import java.util.concurrent.CompletionStage
import java.util.concurrent.atomic.AtomicBoolean

/** Seconds between websocket pings on a console channel. A ping still unanswered at the next period ends the session. */
internal const val WEBSOCKET_PING_SECONDS = 30L
internal val WEBSOCKET_PING_PAYLOAD = "ringmaster".toByteArray(Charsets.US_ASCII)

/**
 * Websocket keepalive for a console channel (the VNC console and the text terminal share it). Each [tick] sends one ping;
 * a tick that finds the previous ping still unanswered, or a ping that cannot be sent, reports [lost]. Nothing is retried
 * and no new console ticket is requested.
 *
 * Writes into a dead connection succeed until the operating system gives up (minutes), so a computer that slept or a
 * network mapping that disappeared leaves a channel that looks connected. A ping that gets no answer for one period
 * (30 to 60 seconds in all) is how that is noticed.
 */
internal class WebSocketKeepalive(private val ping: () -> CompletionStage<*>, private val lost: () -> Unit) {
    private val waiting = AtomicBoolean(false)
    private val ended = AtomicBoolean(false)
    fun tick() {
        if (ended.get()) return
        if (waiting.getAndSet(true)) { end(); return }
        try { ping().whenComplete { _, error -> if (error != null) failed(error) } }
        catch (e: Exception) { failed(e) }
    }
    /** IllegalStateException only says another ping or pong is still being sent (or the socket is closing): skip one beat. */
    private fun failed(error: Throwable) {
        val root = if (error is java.util.concurrent.CompletionException && error.cause != null) error.cause!! else error
        if (root is IllegalStateException) waiting.set(false) else end()
    }
    /** The server answered the ping, or sent anything at all: either proves it is still there. */
    fun pong() { waiting.set(false) }
    private fun end() { if (ended.compareAndSet(false, true)) lost() }
}
