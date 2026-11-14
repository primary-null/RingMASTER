@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class)
package local.ringleader.desktop

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ShowcaseSemanticsTest {
    @Test fun concealedLabelsAndEditorsExposeOnlyHiddenToAccessibility() = runSceneTest {
        val label = "private-node-canary"
        val value = "private-url-query-canary"
        val scene = ImageComposeScene(800, 500, coroutineContext = coroutineContext) {
            CompositionLocalProvider(LocalReduceMotion provides true) {
                Column {
                    Text(label, Modifier.identity())
                    BasicTextField(value, {}, Modifier.identity())
                    Text("Public heading")
                }
            }
        }
        fun accessibleText(): List<String> = buildList {
            fun visit(n: SemanticsNode) {
                addAll(n.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text })
                addAll(n.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty())
                n.config.getOrNull(SemanticsProperties.EditableText)?.text?.let(::add)
                n.children.forEach(::visit)
            }
            scene.semanticsOwners.forEach { visit(it.rootSemanticsNode) }
        }
        try {
            Appearance.preview(false, showcase = false)
            val driver = StorageSceneDriver(scene, storageLooks().first())
            driver.settle()
            assertTrue(accessibleText().any { label in it })
            assertTrue(accessibleText().any { value in it })
            Appearance.preview(false, showcase = true)
            driver.settle()
            val hidden = accessibleText()
            assertFalse(hidden.any { label in it || value in it }, "Hidden content leaked through accessibility: $hidden")
            assertTrue("Hidden" in hidden)
            assertTrue("Public heading" in hidden)
            Appearance.preview(false, showcase = false)
            driver.settle()
            assertTrue(accessibleText().any { label in it })
            assertTrue(accessibleText().any { value in it })
        } finally { Appearance.preview(false); scene.close() }
    }
}
