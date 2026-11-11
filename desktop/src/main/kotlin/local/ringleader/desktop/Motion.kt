package local.ringleader.desktop
import androidx.compose.runtime.*
import java.time.Instant
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import local.ringleader.core.profileFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
val LocalReduceMotion = staticCompositionLocalOf { false }
val LocalSetReduceMotion = staticCompositionLocalOf<(Boolean)->Unit> { {} }

/** Wall clock for "synced Ns ago" and relative task times. Null means read [Instant.now] once. */
val LocalNow = compositionLocalOf<Instant?> { null }

/**
 * One shared frame phase. Draw sites read [pulse], [spin] and [shimmer]; they must not read them during composition.
 * [active] is false when motion is reduced, the window is minimized, or the window is unfocused, and the frame loop is stopped.
 */
class MotionPhase {
    val pulse = mutableFloatStateOf(0.35f)
    val spin = mutableFloatStateOf(0f)
    val shimmer = mutableFloatStateOf(0f)
    val active = mutableStateOf(false)
    /** Ambient effects that need the clock hold it (see [HoldAmbientClock]); it only matters when the clock is demand driven. */
    val demand = mutableIntStateOf(0)
}

/** Keep the shared ambient clock running while this is composed and [needed]. A no-op unless [ProvideDeskClock] is demand driven. */
@Composable fun HoldAmbientClock(needed: Boolean = true) {
    val phase = LocalMotionPhase.current
    DisposableEffect(phase, needed) {
        if (needed) phase.demand.intValue++
        onDispose { if (needed) phase.demand.intValue-- }
    }
}

val LocalMotionPhase = staticCompositionLocalOf { MotionPhase() }

/** Breathing wave of the shared phase: 0.15..0.60, one cycle per [MotionDur.AmbientPeriod] (0.83 Hz, far under 3 flashes a second). */
internal fun pulseAt(seconds: Double): Float =
    0.15f + 0.45f * ((kotlin.math.sin(seconds * Math.PI * 2.0 / (MotionDur.AmbientPeriod / 1000.0)) + 1.0) / 2.0).toFloat()

private fun MotionPhase.apply(seconds: Double) {
    pulse.floatValue = pulseAt(seconds)
    spin.floatValue = ((seconds / (MotionDur.AmbientPeriod / 1000.0) * 360.0) % 360.0).toFloat()
    shimmer.floatValue = ((seconds % 1.4) / 1.4).toFloat()
}

/** Milliseconds between ambient phase updates: a slow 0.83 Hz breath does not need the display rate (about 32 Hz; 16 Hz on software rendering). */
fun ambientTickMs(tier: MotionTier): Long = if (tier == MotionTier.Lite) MotionDur.LiteTick.toLong() else MotionDur.AmbientTick.toLong()

/**
 * Provides the shared phase and the wall clock. The phase loop runs only while [animations] is true (Main.kt passes false for
 * Reduce motion, an unfocused window and a minimised window, so nothing animates in the background). It ticks at about 32 Hz, or about
 * 16 Hz on the Lite tier (software rendering, where every frame is a full-window raster), instead of at every display frame:
 * on a 240 Hz display a perpetual per-frame loop would repaint the window 240 times a second for one breathing dot.
 *
 * With [demandDriven] the loop also stops while no ambient effect holds the clock ([HoldAmbientClock]) and [MotionPhase.active]
 * is false then, so the whole app idles at zero frames. Leave it false until every consumer of the phase holds the clock.
 */
@Composable fun ProvideDeskClock(animations: Boolean, tickSeconds: Boolean, demandDriven: Boolean = false, content: @Composable () -> Unit) {
    val phase = remember { MotionPhase() }
    val tier = LocalMotion.current.tier
    var now by remember { mutableStateOf(Instant.now()) }
    val running = animations && (!demandDriven || phase.demand.intValue > 0)
    LaunchedEffect(running, tier) {
        phase.active.value = running
        if (!running) {
            phase.pulse.floatValue = 0.35f
            phase.spin.floatValue = 0f
            phase.shimmer.floatValue = 0f
            return@LaunchedEffect
        }
        val start = System.nanoTime()
        val tick = ambientTickMs(tier)
        while (isActive) {
            phase.apply((System.nanoTime() - start) / 1_000_000_000.0)
            delay(tick)
        }
    }
    LaunchedEffect(tickSeconds) {
        now = Instant.now()
        if (!tickSeconds) return@LaunchedEffect
        while (isActive) {
            delay(1000)
            now = Instant.now()
        }
    }
    CompositionLocalProvider(LocalMotionPhase provides phase, LocalNow provides now, content = content)
}
class MotionPreferences(private val file: java.nio.file.Path = profileFile().resolveSibling("motion.txt")) {
 private fun loadProperties() = ConfigSchemas.properties(file).apply {
  if (runCatching { Files.readString(file).trim() == "reduce" }.getOrDefault(false)) setProperty("reduce","true")
 }
 private var props = loadProperties()
 private fun latest(): java.util.Properties {
  if (ConfigSchemas.writable(file)) props = loadProperties()
  return props
 }
 fun option(key:String,default:Boolean) = latest().getProperty(key)?.toBooleanStrictOrNull() ?: default
 fun number(key:String,default:Int) = latest().getProperty(key)?.toIntOrNull() ?: default
 fun put(key:String,value:Boolean) = put(key,value.toString())
 fun putNumber(key:String,value:Int) = put(key,value.toString())
 private fun put(key:String,value:String) {
  latest().setProperty(key,value)
  if (!ConfigSchemas.writable(file)) return
  runCatching {
   // Match the legacy store: a corrupt properties document is not replaced by a save.
   if (Files.exists(file)) {
    val old=Files.readString(file)
    if (old.trim() != "reduce") java.io.StringReader(old).use { java.util.Properties().load(it) }
   }
   props.setProperty("schema","1"); Files.createDirectories(file.parent)
   val temp=Files.createTempFile(file.parent,"motion-",".tmp")
   try { Files.newBufferedWriter(temp).use { props.store(it,null) }; Files.move(temp,file,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE) } finally { Files.deleteIfExists(temp) }
  }.onFailure { SaveStatus.report("Preferences",it) }
 }
 fun read() = option("reduce",false)
 fun write(reduce:Boolean) { put("reduce",reduce) }
}
