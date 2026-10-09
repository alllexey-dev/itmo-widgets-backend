package dev.alllexey.itmowidgets.backend.feature.push.persistence

/**
 * A native aggregate row of [DeviceRepository.countActiveByClientBuild]: one reported build and its device count.
 * Every column but [devices] is null for devices that never reported a build.
 */
interface ClientBuildCount {
    val platform: String?
    val distribution: String?
    val version: String?
    val build: Int?
    val devices: Long
}
