package dev.alllexey.itmowidgets.backend.feature.push.model

/** Who delivers to a device's token; [NONE] is a device without a token, which no push reaches. */
enum class PushProvider { FCM, NONE }
