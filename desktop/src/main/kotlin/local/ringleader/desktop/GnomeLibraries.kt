package local.ringleader.desktop

import com.sun.jna.Library
import com.sun.jna.Native

/**
 * GObject registers process-global type metadata containing pointers into its libraries. Letting JNA unload
 * a short-lived vault or bus proxy leaves those registrations dangling when another service loads GIO later.
 * Keep successful proxies (and their dependency libraries) alive until process exit.
 */
internal object GnomeLibraries {
    private val loaded = mutableMapOf<Class<*>, Library>()

    @Synchronized fun <T : Library> load(type: Class<T>, vararg names: String): T {
        loaded[type]?.let { return type.cast(it) }
        var last: UnsatisfiedLinkError? = null
        for (name in names) {
            try {
                return Native.load(name, type).also { loaded[type] = it }
            } catch (e: UnsatisfiedLinkError) { last = e }
        }
        throw last ?: UnsatisfiedLinkError(names.first())
    }
}
