@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package local.ringleader.desktop.web

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.input.key.*
import androidx.compose.ui.unit.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import local.ringleader.desktop.*
import org.jetbrains.skia.*
import java.nio.ByteBuffer

class TileDiff {
    private val previous = mutableMapOf<Pair<Int, Int>, ByteArray>()
    fun reset() = previous.clear()
    fun frame(image: Image, width: Int = image.width, height: Int = image.height): List<ByteArray> = buildList {
        for(y in 0 until height step 64) for(x in 0 until width step 64) {
            val w = minOf(64, width-x); val h = minOf(64, height-y)
            val png = Surface.makeRasterN32Premul(w,h).use { tile -> tile.canvas.drawImage(image, -x.toFloat(), -y.toFloat()); tile.makeImageSnapshot().use { it.encodeToData(EncodedImageFormat.PNG)!!.use { it.bytes } } }
            if(previous[x to y]?.contentEquals(png) != true) {
                previous[x to y] = png
                add(ByteBuffer.allocate(16+png.size).putInt(x).putInt(y).putInt(w).putInt(h).put(png).array())
            }
        }
    }
}
class WebScene : AutoCloseable {
    private val dispatcher = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r,"browser-scene").apply { isDaemon=true } }.asCoroutineDispatcher()
    override fun close() = dispatcher.close()
    suspend fun run(socket: WebSocketSession) = withContext(dispatcher) {
        val host = AppHost(true)
        var dpr by androidx.compose.runtime.mutableStateOf(1f)
        // Constraints can change without disposing the composition or its remembered Workspace/ProfileDesk.
        var width = 960; var height = 640
        val scene = ImageComposeScene(3840,2160, density = Density(1f), coroutineContext = coroutineContext) { androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides Density(dpr)) { RingApp(host) } }
        scene.constraints = Constraints.fixed(width,height)
        val diff = TileDiff()
        var forceRender = true
        var lastInput = System.nanoTime()
        try {
            while(isActive) {
                val started = System.nanoTime()
                var next = socket.incoming.tryReceive().getOrNull()
                var count = 0
                while(next != null && count++ < 128) {
                    if(next is Frame.Text) {
                        lastInput = System.nanoTime()
                        val msg = Json.parseToJsonElement(next.readText()).jsonObject
                        fun number(k: String, default: Float = 0f) = msg[k]?.jsonPrimitive?.floatOrNull ?: default
                        fun str(k: String) = msg[k]?.jsonPrimitive?.content.orEmpty()
                        when(str("t")) {
                            "resize" -> {
                                dpr = number("dpr",1f).coerceIn(.5f,3f)
                                val w = (number("w",960f).coerceIn(160f,2560f)*dpr).toInt().coerceAtMost(3840)
                                val h = (number("h",640f).coerceIn(120f,1600f)*dpr).toInt().coerceAtMost(2160)
                                width=w; height=h
                                scene.constraints = Constraints.fixed(w,h)
                                // Density supplied inside the content preserves remembered state across DPR changes.

                                diff.reset(); forceRender = true
                            }
                            "pointer", "wheel" -> {
                                val kind = str("kind")
                                val type = if(str("t")=="wheel") PointerEventType.Scroll else when(kind) { "down" -> PointerEventType.Press; "up" -> PointerEventType.Release; "leave" -> PointerEventType.Exit; else -> PointerEventType.Move }
                                val buttons = number("buttons").toInt()
                                val mods = msg["mods"]?.jsonObject
                                fun mod(k: String) = mods?.get(k)?.jsonPrimitive?.booleanOrNull ?: false
                                scene.sendPointerEvent(type, Offset(number("x")*dpr,number("y")*dpr), scrollDelta = Offset(number("dx")/100f,number("dy")/100f), keyboardModifiers = PointerKeyboardModifiers(isCtrlPressed=mod("ctrl"),isShiftPressed=mod("shift"),isAltPressed=mod("alt"),isMetaPressed=mod("meta")), buttons = PointerButtons(isPrimaryPressed=buttons and 1 != 0, isSecondaryPressed=buttons and 2 != 0, isTertiaryPressed=buttons and 4 != 0), button = when(number("button").toInt()) { 1 -> PointerButton.Tertiary; 2 -> PointerButton.Secondary; else -> PointerButton.Primary })
                            }
                            "key" -> {
                                val mods = msg["mods"]?.jsonObject
                                fun mod(k: String) = mods?.get(k)?.jsonPrimitive?.booleanOrNull ?: false
                                val key = browserKey(str("key"),str("code"))
                                val event = KeyEvent(key, if(str("kind")=="up") KeyEventType.KeyUp else KeyEventType.KeyDown, isCtrlPressed=mod("ctrl"),isShiftPressed=mod("shift"),isAltPressed=mod("alt"),isMetaPressed=mod("meta"))
                                if(host.shortcuts?.invoke(event) != true) scene.sendKeyEvent(event)
                            }
                            "text" -> sendText(scene,str("text"))
                        }
                    }
                    next = socket.incoming.tryReceive().getOrNull()
                }
                if(socket.incoming.isClosedForReceive || System.nanoTime()-lastInput > 1800000000000L) break
                if(forceRender || scene.hasInvalidations()) {
                    forceRender = false
                    scene.render(System.nanoTime()).use { image -> for(tile in diff.frame(image,width,height)) socket.send(Frame.Binary(true,tile)) }
                }
                delay(((33333334L-(System.nanoTime()-started))/1000000).coerceAtLeast(1))
            }
        } finally { scene.close() }
    }
}
fun sendText(scene: ImageComposeScene, text: String) {
    for(char in text.take(8192)) {
        val native = java.awt.event.KeyEvent(java.awt.Canvas(), java.awt.event.KeyEvent.KEY_TYPED, System.currentTimeMillis(), 0, java.awt.event.KeyEvent.VK_UNDEFINED, char)
        scene.sendKeyEvent(native.toComposeEvent())
    }
}
fun browserKey(key: String, code: String): Key {
    val awt = when(key) {
        "ArrowLeft" -> 37; "ArrowUp" -> 38; "ArrowRight" -> 39; "ArrowDown" -> 40
        "Enter" -> 10; "Escape" -> 27; "Backspace" -> 8; "Delete" -> 127; "Tab" -> 9
        "Home" -> 36; "End" -> 35; "PageUp" -> 33; "PageDown" -> 34
        else -> when { code.matches(Regex("Key[A-Z]")) -> code.last().code; code.matches(Regex("Digit[0-9]")) -> code.last().code; key.matches(Regex("F([1-9]|1[0-2])")) -> 111+key.drop(1).toInt(); key.length==1 -> java.awt.event.KeyEvent.getExtendedKeyCodeForChar(key.uppercase()[0].code); else -> 0 }
    }
    return java.awt.event.KeyEvent(java.awt.Canvas(), java.awt.event.KeyEvent.KEY_PRESSED, 0, 0, awt, java.awt.event.KeyEvent.CHAR_UNDEFINED).toComposeEvent().key
}
