package dev.alllexey.itmowidgets.backend.model

/** Bounded operational categories, never provider messages or response payloads. */
enum class SportUpdateErrorCategory { AUTH, NETWORK, HTTP, MAPPING, PERSISTENCE, INTERNAL }
