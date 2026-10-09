package dev.alllexey.itmowidgets.backend.feature.push.model

import dev.alllexey.itmowidgets.backend.feature.app.model.AppPlatform

/**
 * The app build a request came from, as the `X-App-Version` header names it:
 * `<versionName> (<versionCode>); <platform>; <distribution>`, for example `2.3.0-beta.1 (20291); android; github`.
 * Android 2.2 and older send no header.
 */
data class ClientVersion(val version: String, val build: Int, val platform: AppPlatform, val distribution: String) {
    companion object {
        const val HEADER = "X-App-Version"
        const val MAX_HEADER_LENGTH = 100
        const val MAX_VERSION_LENGTH = 32

        private val PLATFORMS = mapOf("android" to AppPlatform.ANDROID, "ios" to AppPlatform.IOS)
        private val FORMAT = Regex(
            """^([0-9]{1,4}(?:\.[0-9]{1,4}){1,3}(?:-[0-9A-Za-z.]{1,16})?) \(([1-9][0-9]{0,8})\); (android|ios); ([a-z][a-z0-9-]{0,15})$""",
        )

        /** The header's value, or null when it is absent, longer than [MAX_HEADER_LENGTH] or not in the format. */
        fun parse(header: String?): ClientVersion? {
            if (header == null || header.length > MAX_HEADER_LENGTH) return null
            val (version, build, platform, distribution) = FORMAT.matchEntire(header)?.destructured ?: return null
            if (version.length > MAX_VERSION_LENGTH) return null
            return ClientVersion(version, build.toInt(), PLATFORMS.getValue(platform), distribution)
        }
    }
}
