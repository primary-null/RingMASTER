package local.ringleader.core

/** Which certificate a saved profile connects with, without any certificate observed or approved right now. */
sealed interface ProfilePin {
    /** A pin: the profile's own, or the single certificate approved for its server. [spki] is its recorded key, if any. */
    data class Pinned(val pin: String, val spki: String?) : ProfilePin
    /** Nothing is known for this server: normal TLS ([PlatformTrust]) decides. */
    data object NormalTls : ProfilePin
    /** Different certificates are trusted for this server; they must be reconciled on the Desk first. */
    data object Conflict : ProfilePin
}

/**
 * The certificate [profile] connects with. A profile with its own pin uses it. A profile without one uses what the Connect
 * form trusts for its address and port: the pins of other saved profiles to that server and the approval in [approved]
 * (certificates.json). One known certificate is used; none means normal TLS; more than one is a conflict and nothing
 * connects. Nothing observed and nothing newly approved takes part, so this is what a background run may use. The Desk
 * adds the explicit, observed approval on top of it ([ProfileDesk]).
 */
fun resolveProfilePin(profile: ConnectionProfile, approved: List<ApprovedCertificate>, profiles: List<ConnectionProfile>): ProfilePin {
    profile.sha256Pin?.let { return ProfilePin.Pinned(it, profile.sha256Spki) }
    val origin = certificateOrigin(profile.origin)
    val atOrigin = profiles.filter { runCatching { certificateOrigin(it.origin) }.getOrNull() == origin }
    val entry = approved.find { it.origin == origin }
    val keys = atOrigin.mapNotNull { p -> p.sha256Pin?.let { pin -> pin to p.sha256Spki } } + listOfNotNull(entry?.let { it.pin to it.spki })
    val pins = keys.map { it.first }.toSet()
    return when (pins.size) {
        0 -> ProfilePin.NormalTls
        1 -> ProfilePin.Pinned(pins.single(), keys.firstNotNullOfOrNull { (pin, spki) -> spki?.takeIf { pin == pins.single() } })
        else -> ProfilePin.Conflict
    }
}

/** What a background run says when [resolveProfilePin] gives no usable pin. */
internal const val SCHEDULE_PIN_CONFLICT = "Different certificates are trusted for this server; review them on the Desk. Nothing was sent."
internal const val SCHEDULE_TRUST_UNREADABLE = "Remembered certificates could not be read; review certificates.json. Nothing was sent."
