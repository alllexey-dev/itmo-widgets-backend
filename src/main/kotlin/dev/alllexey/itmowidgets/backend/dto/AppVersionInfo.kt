package dev.alllexey.itmowidgets.backend.dto

/** App release metadata. Note is plain text, not HTML or Markdown; it may be empty. */
data class AppVersionInfo(
    val minVersion: String,
    val latestVersion: String,
    val note: String,
)
