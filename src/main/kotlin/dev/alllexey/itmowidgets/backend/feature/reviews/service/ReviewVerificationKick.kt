package dev.alllexey.itmowidgets.backend.feature.reviews.service

/** Queues the ISU checks of due reviews and flow links; called after a save commits. */
fun interface ReviewVerificationKick {
    fun kick()
}
