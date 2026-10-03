package dev.alllexey.itmowidgets.backend.feature.reviews.service

/** Queues the ISU check of due reviews; called after a save commits. */
fun interface ReviewVerificationKick {
    fun kick()
}
