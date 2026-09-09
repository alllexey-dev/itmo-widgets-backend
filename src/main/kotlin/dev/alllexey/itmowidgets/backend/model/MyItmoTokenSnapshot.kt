package dev.alllexey.itmowidgets.backend.model

/** Immutable credential bundle. Its textual representation must never expose credentials. */
class MyItmoTokenSnapshot(
    val accessToken: String?,
    val accessExpiresAt: Long,
    val refreshToken: String?,
    val refreshExpiresAt: Long,
    val idToken: String?,
) {
    override fun toString(): String = "MyItmoTokenSnapshot(redacted)"
}
