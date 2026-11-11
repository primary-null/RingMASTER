package local.ringleader.core

import kotlin.test.Test
import kotlin.test.assertEquals

class LinuxCursorFilterTest {
    private val esc = "\u001b"
    private fun run(vararg chunks: String): String {
        val f = LinuxCursorFilter()
        return chunks.joinToString("") { String(f.filter(it.toByteArray()), Charsets.UTF_8) }
    }

    @Test fun dropsLinuxCursorShapeSequences() {
        assertEquals("ab", run("a$esc[?6cb"))
        assertEquals("ab", run("a$esc[?25;0;8cb"))
    }

    @Test fun sequenceSplitAcrossChunksIsDropped() {
        assertEquals("ab", run("a$esc", "[?6cb"))
        assertEquals("ab", run("a$esc[", "?6", "cb"))
        assertEquals("ab", run("a$esc[?", "6c", "b"))
    }

    @Test fun ordinaryXtermSequencesPassThrough() {
        val text = "$esc[?25l$esc[?1049h$esc[31mred$esc[0m$esc[c$esc[2J"
        assertEquals(text, run(text))
        assertEquals("x$esc[?1049hy", run("x$esc", "[?1049hy"))
    }

    @Test fun multibyteTextIsUntouched() {
        assertEquals("héllo · ✓", run("héllo · ✓"))
    }
}
