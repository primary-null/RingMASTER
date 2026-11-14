package local.ringleader.desktop

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.SnapSpec
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.TweenSpec
import java.nio.file.Files
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.jetbrains.skiko.GraphicsApi

class MotionSystemTest {
    // ---- tokens -------------------------------------------------------------------------------------------------

    @Test fun durationTokensMatchTheBrief() {
        assertEquals(70, MotionDur.Micro); assertEquals(110, MotionDur.Quick); assertEquals(150, MotionDur.Short)
        assertEquals(240, MotionDur.Standard); assertEquals(400, MotionDur.Emphasis); assertEquals(1200, MotionDur.AmbientPeriod)
        assertTrue(1000.0 / MotionDur.AmbientPeriod < 3.0, "the breathing cycle stays far under three flashes a second")
    }

    @Test fun easingAndSpringTokensAreTheDocumentedValues() {
        assertEquals(CubicBezierEasing(0.2f, 0f, 0f, 1f), MotionEase.Standard)
        assertEquals(CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f), MotionEase.Enter)
        assertEquals(CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f), MotionEase.Exit)
        assertEquals(CubicBezierEasing(0.2f, 0f, 0.38f, 0.9f), MotionEase.Productive)
        assertEquals(0.9f to 1400f, MotionSpring.SpatialFast.let { it.damping to it.stiffness })
        assertEquals(0.9f to 700f, MotionSpring.SpatialDefault.let { it.damping to it.stiffness })
        assertEquals(0.9f to 300f, MotionSpring.SpatialSlow.let { it.damping to it.stiffness })
        assertEquals(0.6f to 800f, MotionSpring.Pop.let { it.damping to it.stiffness })
        assertEquals(listOf(3800f, 1600f, 800f), listOf(MotionSpring.EffectsFast, MotionSpring.EffectsDefault, MotionSpring.EffectsSlow).map { it.stiffness })
        assertTrue(listOf(MotionSpring.EffectsFast, MotionSpring.EffectsDefault, MotionSpring.EffectsSlow).all { it.damping == 1f }, "effects springs are critically damped")
        // Only the pop spring may overshoot.
        assertTrue(listOf(MotionSpring.SpatialFast, MotionSpring.SpatialDefault, MotionSpring.SpatialSlow).all { it.damping >= .9f })
    }

    @Test fun springStepReproducesTheDocumentedPop() {
        val peak = (1..400).maxOf { springStep(0.6f, 800f, it / 1000f) }
        val expected = 1f + exp(-PI.toFloat() * 0.6f / sqrt(1f - 0.36f))
        assertTrue(abs(peak - expected) < 0.003f, "pop overshoots about 9.5% (peak $peak, expected $expected)")
        assertTrue((1..600).maxOf { springStep(1f, 1600f, it / 1000f) } <= 1.0001f, "a critically damped spring never overshoots")
        assertTrue(abs(springStep(0.9f, 700f, 0.4f) - 1f) < 0.01f)
    }

    // ---- tiers --------------------------------------------------------------------------------------------------

    @Test fun movementTokensBecomeSnapsUnderReducedAndSpringsOtherwise() {
        listOf(MotionTier.Full, MotionTier.Lite).forEach { tier ->
            val m = RmMotion(tier)
            assertTrue(m.spatial<Float>() is SpringSpec<*>, "$tier spatial")
            assertTrue(m.pop<Float>() is SpringSpec<*>, "$tier pop")
        }
        val reduced = RmMotion(MotionTier.Reduced)
        assertTrue(reduced.spatial<Float>() is SnapSpec<*>)
        assertTrue(reduced.pop<Float>() is SnapSpec<*>)
        assertTrue(reduced.spatial<Float>(MotionSpring.SpatialFast) is SnapSpec<*>)
    }

    @Test fun opacityAndColourTokensSurviveEveryTier() {
        MotionTier.entries.forEach { tier ->
            val m = RmMotion(tier)
            val effects = m.effects<Float>()
            assertTrue(effects is SpringSpec<*>, "$tier effects is a spring, never a snap")
            assertEquals(1f, (effects as SpringSpec<*>).dampingRatio)
            val fade = m.fade<Float>()
            assertTrue(fade is TweenSpec<*>, "$tier fade keeps a fade")
            assertTrue((fade as TweenSpec<*>).durationMillis in 1..MotionDur.Quick)
        }
    }

    @Test fun flourishDurationsResolvePerTier() {
        assertEquals(240, RmMotion(MotionTier.Full).ms(240))
        assertEquals(180, RmMotion(MotionTier.Lite).ms(240))
        assertEquals(110, RmMotion(MotionTier.Reduced).ms(240), "Reduced keeps at most the fade length")
        assertEquals(70, RmMotion(MotionTier.Reduced).ms(70))
        assertEquals((240 * 0.65f).toInt(), (RmMotion(MotionTier.Full).exit(240) as TweenSpec<*>).durationMillis)
        assertEquals(240, (RmMotion(MotionTier.Full).enter(240) as TweenSpec<*>).durationMillis)
        assertEquals(180, (RmMotion(MotionTier.Lite).enter(240) as TweenSpec<*>).durationMillis)
        assertEquals(MotionEase.Enter, (RmMotion(MotionTier.Full).enter() as TweenSpec<*>).easing)
        assertEquals(MotionEase.Exit, (RmMotion(MotionTier.Full).exit() as TweenSpec<*>).easing)
    }

    @Test fun lightStaysButTravelAndRingsDoNotUnderReduced() {
        val full = RmMotion(MotionTier.Full); val lite = RmMotion(MotionTier.Lite); val reduced = RmMotion(MotionTier.Reduced)
        assertEquals(8f, full.travel(8f)); assertEquals(0f, lite.travel(8f), "Lite is a fade only"); assertEquals(0f, reduced.travel(8f))
        assertEquals(1f, full.lightGain); assertEquals(1f, lite.lightGain)
        assertTrue(reduced.lightGain in 0.2f..0.8f, "light stays under Reduced, dimmer")
        assertEquals(listOf(12, 6, 0), listOf(full.ringBudget, lite.ringBudget, reduced.ringBudget))
        assertEquals(listOf(true, true, false), listOf(full.sparkFlies, lite.sparkFlies, reduced.sparkFlies))
        assertEquals(listOf(true, false, false), listOf(full.sparkTrail, lite.sparkTrail, reduced.sparkTrail))
        assertEquals(listOf(true, true, false), listOf(full.ambient, lite.ambient, reduced.ambient))
    }

    @Test fun tierChoiceFollowsReduceThenRenderer() {
        assertEquals(MotionTier.Reduced, motionTierFor(reduce = true, softwareRenderer = false))
        assertEquals(MotionTier.Reduced, motionTierFor(reduce = true, softwareRenderer = true))
        assertEquals(MotionTier.Lite, motionTierFor(reduce = false, softwareRenderer = true))
        assertEquals(MotionTier.Full, motionTierFor(reduce = false, softwareRenderer = false))
        assertTrue(isSoftwareRenderApi(GraphicsApi.SOFTWARE_FAST)); assertTrue(isSoftwareRenderApi(GraphicsApi.SOFTWARE_COMPAT))
        listOf(GraphicsApi.DIRECT3D, GraphicsApi.OPENGL, GraphicsApi.ANGLE, GraphicsApi.VULKAN, GraphicsApi.METAL, GraphicsApi.UNKNOWN).forEach { assertFalse(isSoftwareRenderApi(it), "$it") }
        assertFalse(isSoftwareRenderApi(null))
    }

    // ---- the operating system's preference --------------------------------------------------------------------------

    private fun reg(mask: String) = "\r\nHKEY_CURRENT_USER\\Control Panel\\Desktop\r\n    UserPreferencesMask    REG_BINARY    $mask\r\n\r\n"

    @Test fun windowsMaskBitTwoOfByteFourIsShowAnimations() {
        // Verified on Windows 11 by toggling "Show animations in Windows": 12 is on, 10 is off.
        assertEquals(true, OsAnimationSetting.parseWindowsMask(reg("9E1E078012000000")))
        assertEquals(false, OsAnimationSetting.parseWindowsMask(reg("9E1E078010000000")))
        assertEquals(true, OsAnimationSetting.parseWindowsMask(reg("9e1e078012000000")), "lower case hex")
        // "Adjust for best performance" is the other mask that switches animations off.
        assertEquals(false, OsAnimationSetting.parseWindowsMask(reg("9012038010000000")))
    }

    @Test fun windowsMaskThatCannotBeReadMeansNoPreference() {
        assertNull(OsAnimationSetting.parseWindowsMask(""))
        assertNull(OsAnimationSetting.parseWindowsMask("ERROR: The system was unable to find the specified registry key or value."))
        assertNull(OsAnimationSetting.parseWindowsMask(reg("9E1E")), "too short to hold byte 4")
        assertNull(OsAnimationSetting.parseWindowsMask(reg("9E1E07801")), "odd digit count")
        assertNull(OsAnimationSetting.parseWindowsMask("UserPreferencesMask    REG_SZ    nope"))
    }

    @Test fun gsettingsBooleans() {
        assertEquals(true, OsAnimationSetting.parseGsettings("true\n"))
        assertEquals(false, OsAnimationSetting.parseGsettings("false\n"))
        assertEquals(false, OsAnimationSetting.parseGsettings("  False \r\n"))
        assertNull(OsAnimationSetting.parseGsettings(""))
        assertNull(OsAnimationSetting.parseGsettings("No such schema 'org.gnome.desktop.interface'"))
    }

    @Test fun readUsesTheRightToolPerPlatformAndAbsentToolsMeanNoPreference() {
        var asked: List<String>? = null
        val windows = OsAnimationSetting.read("Windows 11") { asked = it; reg("9E1E078010000000") }
        assertEquals(OsAnimations.Off, windows)
        assertTrue(asked!!.first().endsWith("reg") || asked!!.first().endsWith("reg.exe"))
        assertEquals(listOf("query", "HKCU\\Control Panel\\Desktop", "/v", "UserPreferencesMask"), asked!!.drop(1))

        assertEquals(OsAnimations.On, OsAnimationSetting.read("Linux") { asked = it; "true\n" })
        assertEquals(listOf("gsettings", "get", "org.gnome.desktop.interface", "enable-animations"), asked)
        assertEquals(OsAnimations.Off, OsAnimationSetting.read("Linux") { "false\n" })
        assertEquals(OsAnimations.Unknown, OsAnimationSetting.read("Linux") { null }, "gsettings missing")
        assertEquals(OsAnimations.Unknown, OsAnimationSetting.read("Windows 10") { null }, "reg failed")
        assertEquals(OsAnimations.Unknown, OsAnimationSetting.read("Mac OS X") { error("no tool must run") })
    }

    /** Opt-in (RINGMASTER_PROBE=1): reads this computer's real setting through the same one-shot tool call the app makes. */
    @Test fun readsTheRealSettingOnThisComputer() {
        if (System.getenv("RINGMASTER_PROBE") != "1") return
        val value = kotlinx.coroutines.runBlocking { OsAnimationSetting.refresh() }
        println("OS animations on this computer (${System.getProperty("os.name")}): $value")
        assertEquals(value, OsAnimationSetting.cached)
        val again = kotlinx.coroutines.runBlocking { OsAnimationSetting.refreshIfStale() }
        assertEquals(value, again, "a second call within a minute is answered from the first read")
    }

    @Test fun anExplicitChoiceBeatsTheOperatingSystem() {
        OsAnimations.entries.forEach { os ->
            assertTrue(resolveReduceMotion(true, os), "explicit yes with $os")
            assertFalse(resolveReduceMotion(false, os), "explicit no with $os")
        }
        assertTrue(resolveReduceMotion(null, OsAnimations.Off))
        assertFalse(resolveReduceMotion(null, OsAnimations.On))
        assertFalse(resolveReduceMotion(null, OsAnimations.Unknown))
    }

    @Test fun explicitChoiceIsDetectedFromWhatMotionPreferencesAlreadyShows() {
        assertNull(explicitReduceChoice(asTrue = true, asFalse = false, legacyRead = false), "key missing: both defaults come back")
        assertEquals(true, explicitReduceChoice(asTrue = true, asFalse = true, legacyRead = true))
        assertEquals(false, explicitReduceChoice(asTrue = false, asFalse = false, legacyRead = false))
        assertEquals(true, explicitReduceChoice(asTrue = true, asFalse = false, legacyRead = true), "legacy file holding just the word reduce")

        val dir = Files.createTempDirectory("motion-prefs")
        val file = dir.resolve("motion.txt")
        val untouched = MotionPreferences(file)
        assertNull(explicitReduceChoice(untouched), "a profile that never touched the switch follows the system")
        MotionPreferences(file).write(false)
        assertEquals(false, explicitReduceChoice(MotionPreferences(file)), "an explicit No is still a choice")
        MotionPreferences(file).write(true)
        assertEquals(true, explicitReduceChoice(MotionPreferences(file)))
        val legacy = dir.resolve("legacy.txt").also { Files.writeString(it, "reduce") }
        assertEquals(true, explicitReduceChoice(MotionPreferences(legacy)))
        assertNotNull(dir)
    }

    // ---- the shared breathing phase -------------------------------------------------------------------------------

    @Test fun theBreathingPhaseIsSlowAndLowContrast() {
        assertEquals(pulseAt(0.0), pulseAt(MotionDur.AmbientPeriod / 1000.0), 1e-4f)
        val samples = (0..240).map { pulseAt(it * 0.01) }
        assertTrue(samples.all { it in 0.1499f..0.6001f })
        assertTrue(samples.max() > 0.59f && samples.min() < 0.16f, "it uses its whole range")
        // A full breath takes 1.2 s: at most one up-and-down cycle per 1.2 s, so no more than 0.83 flashes a second.
        val peaks = (1 until samples.lastIndex).count { samples[it] > samples[it - 1] && samples[it] >= samples[it + 1] }
        assertEquals(2, peaks, "two peaks in 2.4 s")
        assertEquals(0f, breath(0.15f)); assertEquals(1f, breath(0.6f)); assertEquals(0.5f, breath(0.375f), 1e-4f)
    }

    // ---- light --------------------------------------------------------------------------------------------------

    @Test fun theAmbientClockTicksSlowerThanTheDisplay() {
        assertEquals(28L, ambientTickMs(MotionTier.Full)); assertEquals(60L, ambientTickMs(MotionTier.Lite))
        assertTrue(1000.0 / ambientTickMs(MotionTier.Full) in 25.0..40.0, "about 30 Hz")
        assertTrue(1000.0 / ambientTickMs(MotionTier.Lite) in 12.0..20.0, "about 15 Hz on software rendering")
        // Just under whole steps of the 15.6 ms Windows timer (31.25 and 62.5 ms), so the loop is not rounded up a step.
        assertTrue(ambientTickMs(MotionTier.Full) < 31L && ambientTickMs(MotionTier.Lite) < 62L)
        // A 0.83 Hz breath sampled at 30 Hz moves less than 1/20 of its range per tick: no visible stepping.
        val step = (0..39).maxOf { kotlin.math.abs(pulseAt((it + 1) * 0.033) - pulseAt(it * 0.033)) }
        assertTrue(step < 0.45f / 10f, "largest step $step")
    }

    @Test fun phosphorRisesFastThenLingers() {
        assertEquals(0f, phosphorEnvelope(0f)); assertEquals(1f, phosphorEnvelope(AFTERGLOW_RISE_MS), 1e-4f)
        assertEquals(0f, phosphorEnvelope(AFTERGLOW_TOTAL_MS.toFloat()), 1e-4f)
        val decay = (60..400 step 10).map { phosphorEnvelope(it.toFloat()) }
        assertTrue(decay.zipWithNext().all { (a, b) -> b <= a + 1e-5f }, "never rises again")
        assertTrue(phosphorEnvelope(60f + 340f * 0.25f) < 0.6f, "falls quickly at first")
        assertTrue(phosphorEnvelope(60f + 340f * 0.9f) > 0f, "and keeps a faint tail like phosphor")
        assertEquals(AFTERGLOW_RISE_MS + AFTERGLOW_DECAY_MS, AFTERGLOW_TOTAL_MS.toFloat(), "the flourish respects the 400 ms ceiling")
        assertEquals(MotionDur.Emphasis, AFTERGLOW_TOTAL_MS)
    }

    @Test fun theLongerYouWaitedTheBiggerTheGlow() {
        val waits = listOf(0L, 300L, 1_000L, 3_000L, 9_999L, 10_000L, 30_000L, 60_000L, 600_000L)
        val plans = waits.map { afterglowPlan(it) }
        assertTrue(plans.zipWithNext().all { (a, b) -> b.intensity >= a.intensity && b.spreadDp >= a.spreadDp }, "intensity and spread never shrink with the wait")
        assertTrue(plans.first().intensity in 0.09f..0.12f, "a routine start is a faint glow")
        assertTrue(plans.last().intensity in 0.28f..0.31f, "a long wait is the brightest (still a soft additive tint)")
        assertEquals(0f, plans.first().spreadDp); assertEquals(10f, plans.last().spreadDp, 0.01f)
        assertEquals(listOf(WaitLevel.Instant, WaitLevel.Instant, WaitLevel.Brief, WaitLevel.Brief, WaitLevel.Brief, WaitLevel.Long, WaitLevel.Long, WaitLevel.Long, WaitLevel.Long), plans.map { it.level })
        assertEquals(plans.map { it.level == WaitLevel.Long }, plans.map { it.sweep }, "only a wait past ten seconds earns the edge sweep")
        assertEquals(0f, waitedFactor(0L)); assertEquals(1f, waitedFactor(60_000L), 1e-4f); assertEquals(1f, waitedFactor(3_600_000L))
        assertTrue(waitedFactor(5_000L) > 0.4f * waitedFactor(60_000L), "the first seconds count most (log curve)")
        assertTrue(waitedFactor(-5L) == 0f)
    }

    @Test fun hardStopIsFasterAndHarderThanAGracefulShutdown() {
        assertTrue(crtCollapseDurationMs(true) < crtCollapseDurationMs(false) / 2)
        // The squash lands (scale 0.08) at 110 ms for a hard stop, 120 ms for a graceful one.
        assertEquals(0.08f, crtCollapseFrame(HARD_COLLAPSE_SQUASH_MS, true).scaleY, 1e-3f)
        assertEquals(0.08f, crtCollapseFrame(COLLAPSE_SQUASH_MS, false).scaleY, 1e-3f)
        // Hard is linear (no ease): halfway through the squash it is halfway down; graceful is already further down (decelerate).
        assertEquals(1f - 0.92f / 2f, crtCollapseFrame(HARD_COLLAPSE_SQUASH_MS / 2f, true).scaleY, 1e-3f)
        assertTrue(crtCollapseFrame(COLLAPSE_SQUASH_MS / 2f, false).scaleY < 1f - 0.92f / 2f)
        // Only a hard stop glitches, and only for one frame.
        assertEquals(2f, crtCollapseFrame(HARD_COLLAPSE_SQUASH_MS + 4f, true).glitchPx)
        assertEquals(0f, crtCollapseFrame(HARD_COLLAPSE_SQUASH_MS + HARD_GLITCH_MS + 4f, true).glitchPx)
        assertTrue((0..700 step 5).all { crtCollapseFrame(it.toFloat(), false).glitchPx == 0f })
        // The line decays to nothing at the end, and the glyph never grows back.
        listOf(true, false).forEach { hard ->
            val end = crtCollapseFrame(crtCollapseDurationMs(hard).toFloat(), hard)
            assertEquals(0f, end.lineAlpha, 1e-3f); assertEquals(0f, end.dotGlow, 1e-3f)
            val ys = (0..crtCollapseDurationMs(hard) step 4).map { crtCollapseFrame(it.toFloat(), hard).scaleY }
            assertTrue(ys.zipWithNext().all { (a, b) -> b <= a + 1e-5f })
        }
        assertEquals(1f, crtCollapseFrame(0f, false).scaleY)
    }

    @Test fun cameraBracketsSnapInHoldAndLeaveWithOneSoftTick() {
        val total = bracketTotalMs().toFloat()
        assertTrue(total <= 700f)
        assertEquals(0f, bracketFrame(total + 1f, false).alpha); assertEquals(0f, bracketFrame(0f, false).alpha)
        val early = bracketFrame(5f, false)
        assertTrue(early.outsideDp > BRACKET_TRAVEL_DP * 0.9f, "starts outside the frame")
        assertTrue(bracketFrame(BRACKET_SNAP_MS + 100f, false).outsideDp.let { abs(it) < 0.7f }, "settled on the frame")
        assertTrue((0..total.toInt() step 5).all { bracketFrame(it.toFloat(), false).tick <= 0.1001f }, "one pulse at most 10% (WCAG 2.3.1)")
        assertTrue((0..total.toInt() step 5).all { bracketFrame(it.toFloat(), true).outsideDp == 0f }, "Reduced: alpha only, no travel")
        assertEquals(1f, bracketFrame(BRACKET_SNAP_MS + BRACKET_HOLD_MS - 1f, true).alpha, 1e-3f)
        assertTrue(bracketFrame(total - 1f, false).alpha < 0.1f)
    }

    @Test fun theNudgeHitsItsKeyframesOnce() {
        assertEquals(0f, nudgeOffsetDp(0f)); assertEquals(-6f, nudgeOffsetDp(50f), 1e-4f); assertEquals(5f, nudgeOffsetDp(110f), 1e-4f)
        assertEquals(-3f, nudgeOffsetDp(180f), 1e-4f); assertEquals(0f, nudgeOffsetDp(280f)); assertEquals(0f, nudgeOffsetDp(900f))
        assertEquals(280, NUDGE_MS)
        // Four direction changes in 280 ms is about 7 Hz of a 6 dp shift of one chip, once: a nudge, not a flash.
        val xs = (0..280 step 2).map { nudgeOffsetDp(it.toFloat()) }
        assertTrue(xs.all { abs(it) <= 6f })
    }

    @Test fun theConsoleOpensFromAThinLineThenGrows() {
        assertEquals(0f, crtRevealFraction(0f)); assertEquals(1f, crtRevealFraction(1f)); assertEquals(1f, crtRevealFraction(7f)); assertEquals(0f, crtRevealFraction(-1f))
        assertEquals(CRT_LINE_FRACTION, crtRevealFraction(CRT_LINE_HOLD), 1e-4f)
        // One 60 Hz frame is 0.07 of the 240 ms reveal: the line is still a line then, so it can be seen before it opens.
        assertTrue(crtRevealFraction(16f / 240f) <= CRT_LINE_FRACTION + 1e-4f)
        assertTrue(crtRevealFraction(0.5f) > 0.6f, "then it opens fast on the entrance curve")
        val xs = (0..100).map { crtRevealFraction(it / 100f) }
        assertTrue(xs.zipWithNext().all { (a, b) -> b >= a - 1e-6f }, "it never closes while opening")
    }

    @Test fun sparkArcsFromTheButtonToTheTarget() {
        val from = androidx.compose.ui.geometry.Offset(400f, 300f); val to = androidx.compose.ui.geometry.Offset(100f, 120f)
        assertEquals(from, sparkPosition(from, to, 0f, 40f)); assertEquals(to, sparkPosition(from, to, 1f, 40f))
        val mid = sparkPosition(from, to, .5f, 40f)
        assertEquals(250f, mid.x, 1e-3f); assertEquals(210f - 40f, mid.y, 1e-3f)
    }

    @Test fun pingRingsFollowThePolarisPulse() {
        assertEquals(0.85f, pingScale(0f)); assertEquals(2.5f, pingScale(1f), 1e-5f)
        assertEquals(1f, pingAlpha(0f)); assertEquals(0f, pingAlpha(1f)); assertEquals(0f, pingAlpha(2f))
    }

    @Test fun pingFieldRateLimitsAndCapsAliveRings() {
        val field = PingField(capacity = 12)
        repeat(40) { field.enqueue(it / 64f, 0.5f, it % 3) }
        field.step(0)
        assertEquals(1, field.alive, "one ring per step")
        field.step(10); assertEquals(1, field.alive, "not before 30 ms have passed")
        field.step(30); assertEquals(2, field.alive)
        var now = 30L
        repeat(60) { now += 30; field.step(now) }
        assertTrue(field.alive <= 12, "never more than 12 alive (alive=${field.alive})")
        var seen = 0
        field.forEachAlive(now) { _, _, tag, progress -> seen++; assertTrue(tag in 0..2); assertTrue(progress in 0f..1f) }
        assertEquals(field.alive, seen)
        // Rings die at 400 ms, and the field goes idle once the queue drains and the last ring is gone.
        repeat(80) { now += 100; field.step(now) }
        assertEquals(0, field.alive); assertFalse(field.busy)
    }

    @Test fun liteAllowsHalfAsManyRingsAndReducedNone() {
        val lite = PingField(RmMotion(MotionTier.Lite).ringBudget)
        repeat(30) { lite.enqueue(0f, 0f) }
        var now = 0L
        repeat(30) { lite.step(now); now += 30 }
        assertEquals(6, lite.alive)
        val reduced = PingField(RmMotion(MotionTier.Reduced).ringBudget)
        repeat(5) { reduced.enqueue(0f, 0f) }
        reduced.step(0)
        assertEquals(0, reduced.alive); assertFalse(reduced.busy)
    }

    @Test fun checkAndProgressHelpersDrawTheRightFractions() {
        val check = checkPath(16f, 16f)
        val measure = androidx.compose.ui.graphics.PathMeasure()
        val scratch = androidx.compose.ui.graphics.Path()
        measure.setPath(check, false)
        val full = measure.length
        assertTrue(full > 10f)
        val half = partialPath(check, 0.5f, measure, scratch)
        val m2 = androidx.compose.ui.graphics.PathMeasure(); m2.setPath(half, false)
        assertEquals(full / 2f, m2.length, full * 0.02f)
        assertTrue(partialPath(check, 0f, measure, scratch).isEmpty)
        val outline = cutOutline(200f, 36f, 8f)
        measure.setPath(outline, true)
        assertTrue(measure.length > 2 * (200f + 36f) - 20f, "the outline is the perimeter with a corner cut")
    }
}
