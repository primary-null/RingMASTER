package local.ringleader.core.integrations

/** Why a link, a request or a test did not give data. Decided from exception classes and status codes, never from message text. */
enum class ProblemKind {
    NameNotResolved, NotReachable, Timeout, WrongScheme,
    CertificateUntrusted, CertificateChanged,
    PlainHttpNeedsAcknowledgement, PlainHttpRefused, AddressRefused, ProxmoxAddress,
    Redirected, NotThisService, UnsupportedVersion,
    CredentialNeeded, CredentialRefused, Forbidden, RateLimited,
    TooLarge, ApiChanged, ServerError, SettingNeeded, RequestRefused, Stopped;

    /** Nothing changes by asking again: polling waits for the owner (a new credential, an acknowledgement, a review). */
    val waitsForOwner get() = this in setOf(CredentialNeeded, CredentialRefused, CertificateUntrusted, CertificateChanged,
        PlainHttpNeedsAcknowledgement, ProxmoxAddress, UnsupportedVersion)

    /** The service did not answer at all, as opposed to answering with something unexpected. */
    val unreachable get() = this in setOf(NameNotResolved, NotReachable, Timeout, WrongScheme)
}

/** One plain sentence with its class. [message] never holds an address, a credential or a query value. */
data class ServiceProblem(val kind: ProblemKind, val message: String, val status: Int? = null, val retryAfterSeconds: Long? = null)

internal fun problemOf(e: ServiceWireException): ServiceProblem = ServiceProblem(
    when (e.failure) {
        WireFailure.NameNotResolved -> ProblemKind.NameNotResolved
        WireFailure.ConnectionRefused, WireFailure.Unreachable, WireFailure.NoAnswer -> ProblemKind.NotReachable
        WireFailure.Timeout -> ProblemKind.Timeout
        WireFailure.TlsFailed -> ProblemKind.WrongScheme
        WireFailure.CertificateChanged -> ProblemKind.CertificateChanged
        WireFailure.CertificateUntrusted -> ProblemKind.CertificateUntrusted
        WireFailure.PlainHttpRefused -> ProblemKind.PlainHttpRefused
        WireFailure.PlainHttpNeedsAcknowledgement -> ProblemKind.PlainHttpNeedsAcknowledgement
        WireFailure.AddressRefused -> ProblemKind.AddressRefused
        WireFailure.TooLarge, WireFailure.TooDeep -> ProblemKind.TooLarge
        WireFailure.EncodingRefused, WireFailure.Malformed -> ProblemKind.NotThisService
        WireFailure.RequestRefused -> ProblemKind.RequestRefused
        WireFailure.Cancelled -> ProblemKind.Stopped
    },
    e.message ?: "The request failed.",
)
