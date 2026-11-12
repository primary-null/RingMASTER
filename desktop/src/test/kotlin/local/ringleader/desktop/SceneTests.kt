package local.ringleader.desktop

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking

/**
 * Runs a scene test on the AWT event thread, in an event loop of its own.
 *
 * Compose Desktop sends snapshot notifications on the AWT thread. A test that draws an ImageComposeScene from the JUnit
 * thread can therefore have two threads inside the scene's layout at once, and Compose then fails at random ("LayoutNode
 * not found in RectList", "performMeasureAndLayout called during measure layout"). Here the test is the AWT thread, as
 * the app is: nothing else can touch the scene while the body runs, and each frame applies the pending changes itself.
 * Pass `coroutineContext` to the scene and `yield()` after each frame so its effects run in this loop.
 */
internal fun runSceneTest(block: suspend CoroutineScope.() -> Unit) {
    var failure: Throwable? = null
    java.awt.EventQueue.invokeAndWait {
        try { runBlocking { block() } } catch (thrown: Throwable) { failure = thrown }
    }
    failure?.let { throw it }
}
