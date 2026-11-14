package local.ringleader.core

/**
 * Public halves of the keys that may sign the update feed (X.509 SubjectPublicKeyInfo, base64, Ed25519). A list, so a
 * key can be rotated: add the new key, release, then drop the old one. The private halves never enter the repository,
 * a build machine or CI; see RELEASING.md. An empty list turns update checks off.
 */
object ReleaseKeys {
    val publicKeys: List<String> = listOf(
        "MCowBQYDK2VwAyEARULXnvwpmVlm/bDGtVvNLvD5/1ytBriBdRmOyhiuS+I=", // generated 2026-11-10
    )
}
