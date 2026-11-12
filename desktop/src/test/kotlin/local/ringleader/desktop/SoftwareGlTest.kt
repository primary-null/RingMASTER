package local.ringleader.desktop

import org.jetbrains.skiko.GraphicsApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Software OpenGL (llvmpipe on a GPU-less VM) selects the Lite tier, and Settings names the renderer. */
class SoftwareGlTest {
    private val none = emptyMap<String, String>()

    @Test fun openGlOnADisplayOnlyAdapterIsSoftware() {
        // QXL (the Proxmox test VMs), Bochs and Cirrus have no Mesa 3D driver: GL there is llvmpipe.
        assertTrue(SoftwareGl.classify(none, listOf("qxl"), null))
        assertTrue(SoftwareGl.classify(none, listOf("bochs-drm"), null))
        assertTrue(SoftwareGl.classify(none, listOf("simpledrm"), null))
        // A real GPU anywhere in the machine keeps hardware GL.
        assertFalse(SoftwareGl.classify(none, listOf("qxl", "i915"), null))
        assertFalse(SoftwareGl.classify(none, listOf("amdgpu"), null))
        assertFalse(SoftwareGl.classify(none, listOf("nvidia"), null))
        // virtio-gpu may have 3D (virgl), so it is not guessed.
        assertFalse(SoftwareGl.classify(none, listOf("virtio_gpu"), null))
        // No card list (not Linux, no sysfs) or no cards: no guess.
        assertFalse(SoftwareGl.classify(none, null, null))
        assertFalse(SoftwareGl.classify(none, emptyList(), null))
    }

    @Test fun mesaSwitchesAndTheOverrideWin() {
        assertTrue(SoftwareGl.classify(mapOf("LIBGL_ALWAYS_SOFTWARE" to "1"), listOf("i915"), null))
        assertTrue(SoftwareGl.classify(mapOf("GALLIUM_DRIVER" to "llvmpipe"), listOf("amdgpu"), null))
        assertTrue(SoftwareGl.classify(mapOf("MESA_LOADER_DRIVER_OVERRIDE" to "kms_swrast"), null, null))
        assertFalse(SoftwareGl.classify(mapOf("LIBGL_ALWAYS_SOFTWARE" to "0"), listOf("i915"), null))
        assertFalse(SoftwareGl.classify(none, listOf("qxl"), "false"))
        assertTrue(SoftwareGl.classify(none, listOf("amdgpu"), "true"))
        assertTrue(SoftwareGl.drmDrivers(java.nio.file.Paths.get("does-not-exist-${System.nanoTime()}")) == null)
    }

    @Test fun softwareOpenGlGetsTheLiteTierAndReduceMotionStillWins() {
        assertTrue(isSoftwareRenderer(GraphicsApi.OPENGL, softwareGl = true))
        assertFalse(isSoftwareRenderer(GraphicsApi.OPENGL, softwareGl = false))
        // The guess is about OpenGL only: Direct3D, Metal and the rest keep Full.
        assertFalse(isSoftwareRenderer(GraphicsApi.DIRECT3D, softwareGl = true))
        assertFalse(isSoftwareRenderer(null, softwareGl = true))
        // Skiko's own software APIs stay software whatever the guess says.
        assertTrue(isSoftwareRenderer(GraphicsApi.SOFTWARE_FAST, softwareGl = false))
        assertTrue(isSoftwareRenderer(GraphicsApi.SOFTWARE_COMPAT, softwareGl = false))
        assertEquals(MotionTier.Lite, motionTierFor(reduce = false, softwareRenderer = isSoftwareRenderer(GraphicsApi.OPENGL, true)))
        assertEquals(MotionTier.Reduced, motionTierFor(reduce = true, softwareRenderer = isSoftwareRenderer(GraphicsApi.OPENGL, true)))
        assertEquals("OpenGL (software)", renderApiLabel(GraphicsApi.OPENGL, true))
        assertEquals("OpenGL", renderApiLabel(GraphicsApi.OPENGL, false))
    }

    @Test fun theDisplayLineNamesTheRendererAndTheMotionTier() {
        assertEquals("Drawing with Direct3D · full motion", displayDrawingLine(GraphicsApi.DIRECT3D, false, MotionTier.Full))
        assertEquals("Drawing with OpenGL (software) · light motion for software drawing", displayDrawingLine(GraphicsApi.OPENGL, true, MotionTier.Lite))
        assertEquals("Drawing with software drawing · reduced motion", displayDrawingLine(GraphicsApi.SOFTWARE_FAST, false, MotionTier.Reduced))
    }
}
