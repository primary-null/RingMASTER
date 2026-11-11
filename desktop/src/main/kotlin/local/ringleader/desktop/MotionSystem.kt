@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
package local.ringleader.desktop

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.awt.LocalAwtWindow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skiko.GraphicsApi
import kotlin.math.roundToInt

/*
 * RingMASTER motion system (design brief: reports/Motion design and animation tech.md).
 *
 *  - tokens: durations, easing curves and springs (plain spring() values; no Material 3 MotionScheme, it is alpha)
 *  - tiers:  Full (GPU renderer), Lite (software renderer), Reduced (Reduce motion, explicit or from the operating system)
 *  - Reduce motion is never done by zeroing a global animation scale (Compose bug CMP-10888 on 1.12, and it would also
 *    kill the opacity and colour fades that Reduce motion permits). Each token resolves itself per tier instead.
 */

enum class MotionTier { Full, Lite, Reduced }

/** Duration tokens in milliseconds (IBM Carbon productive scale). */
object MotionDur {
    /** Press, hover, focus ring. */
    const val Micro = 70
    /** Fades, tint changes, Reduced-mode icon swaps. */
    const val Quick = 110
    /** Row state change, check stroke-draw, exits of [Standard]. */
    const val Short = 150
    /** Toast in, label morph, spark flight, console reveal. */
    const val Standard = 240
    /** Ceiling for any signature flourish. */
    const val Emphasis = 400
    /** Breathing cycle: 1.2 s is 0.83 Hz, far under the WCAG 2.3.1 three-flashes-a-second limit. */
    const val AmbientPeriod = 1200
    /** Exits run at about 0.65 of the entrance, on the accelerate curve. */
    const val ExitFactor = 0.65f
    /**
     * Software rendering redraws the whole window per frame, so ambient phases tick at about 16 Hz there. delay() on the UI thread runs
     * on the Swing timer, which ticks every 15.6 ms on Windows and rounds a wait up to the next tick: 33 ms measured as 47 ms. 60 ms
     * (four ticks, 62 ms) leaves room for the loop's own work, as 28 ms does below.
     */
    const val LiteTick = 60
    /** Everywhere else the ambient phase ticks at about 30 Hz (28 ms asked, two Windows timer ticks, 31 ms observed). */
    const val AmbientTick = 28
}

/** Cubic-bezier easing tokens. */
object MotionEase {
    /** Default tween (M3 standard). */
    val Standard = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    /** Entrances and the afterglow decay (M3 emphasized decelerate). */
    val Enter = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    /** Exits (M3 emphasized accelerate). */
    val Exit = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
    /** Tables, menus, list changes (Carbon productive standard). */
    val Productive = CubicBezierEasing(0.2f, 0f, 0.38f, 0.9f)
    /** Constant-rate sweeps and rotation only. */
    val Linear = LinearEasing
}

/** A spring preset: copied values, applied as an ordinary [spring] so no Material 3 type is involved. */
@Immutable
class SpringToken(val damping: Float, val stiffness: Float) {
    fun <T> spec(visibilityThreshold: T? = null): SpringSpec<T> = spring(damping, stiffness, visibilityThreshold)
}

object MotionSpring {
    /** Small moves, chip and badge reposition (about 120 ms). */
    val SpatialFast = SpringToken(0.9f, 1400f)
    /** Spark flight, panel offsets (about 170 ms). */
    val SpatialDefault = SpringToken(0.9f, 700f)
    /** Progress hairline tracking the server percentage (about 260 ms). */
    val SpatialSlow = SpringToken(0.9f, 300f)
    /** Overshoots about 9.5%. Only for the scale of a small glyph (check, pip), never text or panels. */
    val Pop = SpringToken(0.6f, 800f)
    /** Colour, alpha and glow intensity: critically damped. */
    val EffectsFast = SpringToken(1f, 3800f)
    val EffectsDefault = SpringToken(1f, 1600f)
    val EffectsSlow = SpringToken(1f, 800f)
}

/** Light is the signature vocabulary; this is the amber that blooms and the hotter red-amber for a hard stop. */
internal fun hotAmber() = androidx.compose.ui.graphics.lerp(Tech.Amber, Tech.Red, .55f)

/**
 * The tokens resolved for one [MotionTier]. Call sites never branch on the tier themselves:
 * movement tokens become [snap] under Reduced (the caller shows an opacity or colour change instead), opacity and colour
 * tokens survive every tier, and light effects stay but dimmer and without travel.
 */
@Immutable
class RmMotion(val tier: MotionTier) {
    val full get() = tier == MotionTier.Full
    val lite get() = tier == MotionTier.Lite
    val reduced get() = tier == MotionTier.Reduced

    /** A flourish duration: Lite trims a quarter, Reduced keeps at most the fade length. */
    fun ms(base: Int): Int = when (tier) {
        MotionTier.Full -> base
        MotionTier.Lite -> (base * 0.75f).roundToInt()
        MotionTier.Reduced -> minOf(base, MotionDur.Quick)
    }

    /** Opacity and tint changes keep their (short) fade in every tier: WCAG 2.3.3 does not count them as motion. */
    fun <T> fade(base: Int = MotionDur.Quick): FiniteAnimationSpec<T> =
        tween(if (reduced) minOf(base, MotionDur.Quick) else base, easing = MotionEase.Standard)

    /** Colour, alpha and glow intensity as a critically damped spring. Never snaps. */
    fun <T> effects(token: SpringToken = MotionSpring.EffectsDefault): FiniteAnimationSpec<T> = token.spec()

    /** Movement on a spring. Under Reduced there is no movement, so it snaps. */
    fun <T> spatial(token: SpringToken = MotionSpring.SpatialDefault): FiniteAnimationSpec<T> = if (reduced) snap() else token.spec()

    /** Scale of a small glyph, with its overshoot. Snaps under Reduced. */
    fun <T> pop(): FiniteAnimationSpec<T> = if (reduced) snap() else MotionSpring.Pop.spec()

    /** Entrance on the decelerate curve. */
    fun enter(base: Int = MotionDur.Standard): FiniteAnimationSpec<Float> = tween(ms(base), easing = MotionEase.Enter)

    /** Exit: about 0.65 of the entrance, on the accelerate curve. */
    fun exit(base: Int = MotionDur.Standard): FiniteAnimationSpec<Float> =
        tween((ms(base) * MotionDur.ExitFactor).roundToInt().coerceAtLeast(1), easing = MotionEase.Exit)

    /** Tables, menus and list changes. */
    fun productive(base: Int = MotionDur.Short): FiniteAnimationSpec<Float> = tween(ms(base), easing = MotionEase.Productive)

    /** Slide distance for a list or page entrance: a fade only on Lite and Reduced. */
    fun travel(dp: Float): Float = if (full) dp else 0f

    /** How strongly a light effect shows: dimmer under Reduced, because light is permitted but travel is not. */
    val lightGain: Float get() = if (reduced) 0.6f else 1f

    /** The spark may fly (Reduced: the destination pip fades in instead). */
    val sparkFlies: Boolean get() = !reduced

    /** The spark drags a short trail (Full only). */
    val sparkTrail: Boolean get() = full

    /** Radar rings alive at once: none under Reduced, half as many on Lite. */
    val ringBudget: Int get() = when (tier) { MotionTier.Full -> 12; MotionTier.Lite -> 6; MotionTier.Reduced -> 0 }

    /** Ambient loops (breathing) run only on Full and Lite, and only while the window is active. */
    val ambient: Boolean get() = !reduced
}

private val ProvidedMotion = staticCompositionLocalOf<RmMotion?> { null }
private val FullMotion = RmMotion(MotionTier.Full)
private val ReducedMotion = RmMotion(MotionTier.Reduced)

/**
 * The tokens for this part of the app. Under [ProvideMotionSystem] they are resolved for the chosen tier. Where nothing
 * provides them (the browser-served scene, a test scene) they follow [LocalReduceMotion]: Full, or Reduced when that is on,
 * so Reduce motion is honoured even if the wrapper is left out.
 */
object LocalMotion {
    val current: RmMotion
        @Composable @ReadOnlyComposable get() = ProvidedMotion.current ?: if (LocalReduceMotion.current) ReducedMotion else FullMotion
    infix fun provides(value: RmMotion) = ProvidedMotion provides value
}

/** True while the window is focused and not minimised. Finite moments skip their animation otherwise. */
val LocalWindowActive = staticCompositionLocalOf { true }

/** Reduced beats everything; otherwise a software renderer means Lite. */
fun motionTierFor(reduce: Boolean, softwareRenderer: Boolean): MotionTier = when {
    reduce -> MotionTier.Reduced
    softwareRenderer -> MotionTier.Lite
    else -> MotionTier.Full
}

fun isSoftwareRenderApi(api: GraphicsApi?): Boolean = api == GraphicsApi.SOFTWARE_FAST || api == GraphicsApi.SOFTWARE_COMPAT

// ---------------------------------------------------------------------------------------------------------------------
// Operating system preference
// ---------------------------------------------------------------------------------------------------------------------

/** What the operating system says about animations. [Unknown] means no preference (tool absent, other platform). */
enum class OsAnimations { On, Off, Unknown }

/**
 * Windows "Show animations in Windows" is SPI_GETCLIENTAREAANIMATION. Windows keeps it in bit 0x02 of byte 4 of
 * `HKCU\Control Panel\Desktop\UserPreferencesMask` (checked on Windows 11 by toggling the setting: 12 is on, 10 is off).
 * GNOME keeps `org.gnome.desktop.interface enable-animations`. Both are read once, off the UI thread, by a short-lived
 * child process, so there is no native binding and no way to block the window.
 */
object OsAnimationSetting {
    private val windowsMask = Regex("""UserPreferencesMask\s+REG_BINARY\s+([0-9A-Fa-f]+)""")
    private const val RefreshAfterMs = 60_000L
    private val lastStart = AtomicLong(0L)
    @Volatile var cached: OsAnimations = OsAnimations.Unknown
        private set

    /** True when animations are on, false when off, null when the `reg query` text does not hold the mask. */
    internal fun parseWindowsMask(output: String): Boolean? {
        val hex = windowsMask.find(output)?.groupValues?.get(1) ?: return null
        if (hex.length < 10 || hex.length % 2 != 0) return null
        val byte4 = hex.substring(8, 10).toIntOrNull(16) ?: return null
        return (byte4 and 0x02) != 0
    }

    /** `gsettings get` prints `true` or `false`. */
    internal fun parseGsettings(output: String): Boolean? = when (output.trim().lowercase()) {
        "true" -> true
        "false" -> false
        else -> null
    }

    /** [run] returns a command's standard output, or null when the tool is missing or failed. */
    internal fun read(osName: String, run: (List<String>) -> String?): OsAnimations {
        val name = osName.lowercase()
        val on: Boolean? = when {
            name.startsWith("windows") -> run(listOf(regTool(), "query", "HKCU\\Control Panel\\Desktop", "/v", "UserPreferencesMask"))?.let(::parseWindowsMask)
            name.contains("linux") -> run(listOf("gsettings", "get", "org.gnome.desktop.interface", "enable-animations"))?.let(::parseGsettings)
            else -> null
        }
        return when (on) { true -> OsAnimations.On; false -> OsAnimations.Off; null -> OsAnimations.Unknown }
    }

    private fun regTool(): String = System.getenv("SystemRoot")?.let { java.io.File(it, "System32/reg.exe") }?.takeIf { it.isFile }?.path ?: "reg"

    private fun runTool(command: List<String>): String? = try {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        process.outputStream.close()
        if (!process.waitFor(3, TimeUnit.SECONDS)) { process.destroyForcibly(); null }
        else process.inputStream.bufferedReader().use { it.readText() }
    } catch (_: java.io.IOException) { null } catch (_: SecurityException) { null }

    /** Reads now, on the IO dispatcher. Never throws. */
    suspend fun refresh(): OsAnimations = withContext(Dispatchers.IO) {
        lastStart.set(System.currentTimeMillis())
        val value = runCatching { read(System.getProperty("os.name").orEmpty(), ::runTool) }.getOrDefault(OsAnimations.Unknown)
        cached = value
        value
    }

    /** One read at start-up, and another when the window regains focus after a minute: cheap, and it follows a toggled setting. */
    suspend fun refreshIfStale(): OsAnimations {
        val last = lastStart.get()
        return if (last != 0L && System.currentTimeMillis() - last < RefreshAfterMs) cached else refresh()
    }
}

/** An explicit Reduce motion choice always wins; with none, the operating system decides. */
fun resolveReduceMotion(explicit: Boolean?, os: OsAnimations): Boolean = explicit ?: (os == OsAnimations.Off)

/**
 * The saved choice using only what [MotionPreferences] already shows: with the key missing the two defaults come back
 * unchanged (no choice yet), a stored value comes back whatever the default is. The legacy file that holds just the
 * word `reduce` reads as an explicit yes.
 */
fun explicitReduceChoice(asTrue: Boolean, asFalse: Boolean, legacyRead: Boolean): Boolean? = when {
    asTrue && asFalse -> true
    !asTrue && !asFalse -> false
    legacyRead -> true
    else -> null
}

fun explicitReduceChoice(preferences: MotionPreferences): Boolean? =
    explicitReduceChoice(preferences.option("reduce", true), preferences.option("reduce", false), preferences.read())

// ---------------------------------------------------------------------------------------------------------------------
// Window facts: render API, focus, minimised
// ---------------------------------------------------------------------------------------------------------------------

@Composable private fun rememberSoftwareRender(window: ComposeWindow?): Boolean {
    if (window == null) return false
    var api by remember(window) { mutableStateOf(window.renderApi) }
    DisposableEffect(window) {
        var live = true
        // The window has no way to remove this listener, so it stops writing once the effect is gone.
        window.onRenderApiChanged { if (live) api = window.renderApi }
        api = window.renderApi
        onDispose { live = false }
    }
    return isSoftwareRenderApi(api)
}

private fun java.awt.Window.iconified(): Boolean = (this as? java.awt.Frame)?.let { it.extendedState and java.awt.Frame.ICONIFIED != 0 } ?: false

@Composable private fun rememberWindowActive(window: java.awt.Window?): Boolean {
    if (window == null) return true
    var active by remember(window) { mutableStateOf(window.isFocused && !window.iconified()) }
    DisposableEffect(window) {
        fun refresh() { active = window.isFocused && !window.iconified() }
        val focus = object : java.awt.event.WindowFocusListener {
            override fun windowGainedFocus(e: java.awt.event.WindowEvent) = refresh()
            override fun windowLostFocus(e: java.awt.event.WindowEvent) = refresh()
        }
        val state = java.awt.event.WindowStateListener { refresh() }
        window.addWindowFocusListener(focus)
        window.addWindowStateListener(state)
        refresh()
        onDispose { window.removeWindowFocusListener(focus); window.removeWindowStateListener(state) }
    }
    return active
}

/** The Compose window, or null in a test scene and in the browser-served scene. */
@Composable private fun currentAwtWindow(): java.awt.Window? = (LocalAwtWindow.current as Any?) as? java.awt.Window

/**
 * Wrap the app (inside the provider of the saved Reduce motion value) so everything below reads the same facts:
 * [LocalMotion] (tokens resolved for the tier), [LocalReduceMotion] (the saved choice, else the operating system),
 * [LocalWindowActive], the anchor registry and the spark overlay that sits above the whole app.
 *
 * [tierOverride] and [osOverride] exist for tests and probes; the app passes neither.
 */
@Composable fun ProvideMotionSystem(
    tierOverride: MotionTier? = null,
    osOverride: OsAnimations? = null,
    softwareHint: Boolean = false,
    content: @Composable () -> Unit,
) {
    val window = currentAwtWindow()
    val userReduce = LocalReduceMotion.current
    // Choosing Reduce motion in Settings must count as a choice even when it repeats the saved value (off while the
    // operating system says "no animations"), so every use of the setter re-reads the saved choice.
    val saveReduce = LocalSetReduceMotion.current
    var choiceRevision by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    val explicit = remember(userReduce, tierOverride, choiceRevision) { if (tierOverride != null) null else runCatching { explicitReduceChoice(MotionPreferences()) }.getOrDefault(null) }
    val windowActive = rememberWindowActive(window)
    val osState = remember { mutableStateOf(osOverride ?: OsAnimationSetting.cached) }
    LaunchedEffect(windowActive, osOverride) {
        if (osOverride != null) { osState.value = osOverride; return@LaunchedEffect }
        if (windowActive) osState.value = OsAnimationSetting.refreshIfStale()
    }
    val reduce = if (tierOverride != null) tierOverride == MotionTier.Reduced else resolveReduceMotion(explicit ?: if (userReduce) true else null, osState.value)
    val software = rememberSoftwareRender(window as? ComposeWindow) || softwareHint
    val tier = tierOverride ?: motionTierFor(reduce, software)
    val motion = remember(tier) { RmMotion(tier) }
    val anchors = remember { MotionAnchors() }
    val sparks = remember { SparkLauncher() }
    CompositionLocalProvider(
        LocalReduceMotion provides (tier == MotionTier.Reduced),
        LocalSetReduceMotion provides { value -> saveReduce(value); choiceRevision++ },
        LocalMotion provides motion,
        LocalWindowActive provides windowActive,
        LocalMotionAnchors provides anchors,
        LocalSparks provides sparks,
    ) {
        Box(Modifier.fillMaxSize()) {
            content()
            SparkOverlay(sparks, anchors, motion)
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------------
// Anchors: where things are on screen, for the spark and the brackets
// ---------------------------------------------------------------------------------------------------------------------

/** Registry of on-screen places, keyed by whatever identifies them. Reads the live position, so scrolling never leaves it stale. */
class MotionAnchors {
    private val places = HashMap<Any, LayoutCoordinates>()
    internal fun put(key: Any, coordinates: LayoutCoordinates) { places[key] = coordinates }
    internal fun release(key: Any, coordinates: LayoutCoordinates?) { if (places[key] === coordinates) places.remove(key) }
    /** Window-relative bounds, or null when nothing registered under [key] is attached right now. */
    fun bounds(key: Any): Rect? = places[key]?.takeIf { it.isAttached }?.boundsInWindow()
}

val LocalMotionAnchors = staticCompositionLocalOf { MotionAnchors() }

/** Where a guest shows in the fleet list (the spark lands here). */
data class RowAnchor(val resource: local.ringleader.core.ResourceId)
/** Where a guest's status shows on its detail page or inspector (the spark lands here when no row is on screen). */
data class TagAnchor(val resource: local.ringleader.core.ResourceId)
/** A named place. NavigationRail may register `NamedAnchor("rail", "Activity")` on its Activity row. */
data class NamedAnchor(val group: String, val name: String)

/** Register this node as [key] while it is composed. */
@Composable fun Modifier.motionAnchor(key: Any): Modifier {
    val anchors = LocalMotionAnchors.current
    val holder = remember(anchors, key) { arrayOfNulls<LayoutCoordinates>(1) }
    DisposableEffect(anchors, key) { onDispose { anchors.release(key, holder[0]) } }
    return this.onGloballyPositioned { anchors.put(key, it); holder[0] = it }
}
