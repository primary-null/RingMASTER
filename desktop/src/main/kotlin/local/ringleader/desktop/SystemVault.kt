package local.ringleader.desktop

import local.ringleader.core.ForgetOnlyVault
import local.ringleader.core.NoVault
import local.ringleader.core.SecretVault

/** One vault for the whole app; the browser host wraps it so it can only forget. */
internal object SystemVault {
    val instance: SecretVault by lazy {
        val os = System.getProperty("os.name").orEmpty().lowercase()
        runCatching {
            when {
                os.startsWith("windows") -> WindowsCredentialVault()
                "linux" in os -> LinuxSecretServiceVault()
                else -> NoVault("Remember is not available on this system.")
            }
        }.getOrElse { NoVault("The password safe could not be loaded.") }
    }

    fun forHost(web: Boolean): SecretVault = if (web) ForgetOnlyVault(instance) else instance
}
