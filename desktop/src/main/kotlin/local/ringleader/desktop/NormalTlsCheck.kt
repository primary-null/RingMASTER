package local.ringleader.desktop

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.sp
import local.ringleader.core.NormalTls
import local.ringleader.core.ProbedCertificate
import local.ringleader.core.probeNormalTls
import local.ringleader.core.probeTlsCertificate

/**
 * What the Connect form asks the platform about a server whose certificate it has just read. Tests replace it to see when
 * the answer has arrived; nothing else does.
 */
internal var normalTlsProbe: suspend (String) -> NormalTls = { address -> probeNormalTls(address) }

/** How the Desk reads a server's certificate (fingerprint, key, subject, issuer, dates). Tests may replace it. */
internal var certificateProbe: suspend (String) -> ProbedCertificate? = { address -> probeTlsCertificate(address) }

/** Shown above the buttons, instead of "Connect with normal TLS", when the platform has refused the server's certificate. */
internal const val NORMAL_TLS_REFUSED_NOTE =
    "This server has a private certificate, so normal TLS cannot verify it. Compare the fingerprint with the server's own, then use Trust and connect."

@Composable internal fun NormalTlsRefusedNote() {
    Text(NORMAL_TLS_REFUSED_NOTE, color = Tech.Muted, fontSize = 12.sp)
}

/**
 * Whether normal TLS can work for [address]. It is asked only while "Connect with normal TLS" is on offer ([offered]),
 * and nothing waits for it: until the answer arrives this is null and the buttons are what they always were. The answer
 * belongs to [address] and [keys] (the observed certificate, the profile or the kind). When any of them changes the answer
 * is dropped and a probe still running is cancelled, so a late reply for an earlier address or certificate is never shown.
 */
@Composable internal fun rememberNormalTls(address: String, offered: Boolean, vararg keys: Any?): NormalTls? {
    var answer by remember(address, *keys) { mutableStateOf<NormalTls?>(null) }
    LaunchedEffect(address, offered, *keys) { if (offered) answer = normalTlsProbe(address) }
    return answer
}

/** The failure card's line for a session whose loops stopped on a certificate refusal. */
internal const val CERTIFICATE_HOLD_NOTE = "Not retried in the background: the certificate has to be checked first. Try again or connect again when it is sorted."
