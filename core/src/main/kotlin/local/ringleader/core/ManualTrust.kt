package local.ringleader.core

/** A review snapshot; connectManual re-resolves it before accepting explicit approval. */
data class ManualTrust(
    val origin: String? = null,
    val known: List<String> = emptyList(),
    val observed: String? = null,
    val savedPin: Boolean = false,
    val error: String? = null,
    /** Known pin → the SHA-256 of that leaf's public key, for the known pins whose key was recorded. */
    val knownKeys: Map<String, String> = emptyMap(),
    /** What the certificate check read, when it read the observed leaf ([ProbedCertificate.fingerprint] == [observed]). */
    val certificate: ProbedCertificate? = null,
    /**
     * The observed leaf is expired or not yet valid on this computer's clock. It is never offered for acceptance: an
     * expired key may have leaked. The way forward is renewing it on the server or correcting the clock.
     */
    val observedOutOfDate: Boolean = false,
    /**
     * Other server addresses whose approved or pinned certificate is exactly the observed one (network audit M3): the same
     * server under another name, or two machines that share a cloned key. Shown beside the fingerprint; never trust here.
     */
    val sameAs: List<String> = emptyList(),
) {
    val conflict get() = known.size > 1
    val changed get() = observed != null && known.isNotEmpty() && observed !in known
    val needsApproval get() = observed != null && (known.singleOrNull() != observed)
    /** The observed leaf's key, when the check read it. */
    val observedSpki: String? get() = certificate?.takeIf { it.fingerprint == observed }?.spki
    /** The key that was approved with the single known pin, when it was recorded. */
    val knownSpki: String? get() = known.singleOrNull()?.let { knownKeys[it] }
    /**
     * The certificate changed but the server's key did not: what Proxmox does when it renews its own certificate. Still a
     * change that needs an explicit click; it only replaces the replacement checkbox with one clear sentence.
     */
    val sameKey get() = changed && !conflict && knownSpki != null && knownSpki == observedSpki
    /** The certificate changed and the key is known to differ (false when either key is unknown). */
    val keyChanged get() = changed && !conflict && knownSpki != null && observedSpki != null && knownSpki != observedSpki
    // The Connect form never overwrites a saved profile's pin; that profile's own panel and editor do (profileTrust).
    val canApprove get() = error == null && observed != null && !conflict && !observedOutOfDate && (!savedPin || !changed)
}
