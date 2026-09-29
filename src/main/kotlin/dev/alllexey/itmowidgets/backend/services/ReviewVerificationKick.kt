package dev.alllexey.itmowidgets.backend.services

/** Queues the ISU check of due reviews; called after a save commits. */
fun interface ReviewVerificationKick {
    fun kick()
}
