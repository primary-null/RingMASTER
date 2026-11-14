package local.ringleader.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * The operating-system window title. The app's own title bar is drawn separately and always says RingMASTER.
 * Password managers with auto-type match this title. It never carries a secret.
 */
object WindowTitle {
    /** Set by the desk while a saved profile's Connect form is shown; never a secret, never in showcase mode. */
    var connectTo by mutableStateOf<String?>(null)
    val text: String get() = connectTo?.takeIf { !Appearance.showcase }?.let { "RingMASTER · Connect · $it" } ?: "RingMASTER"
}
