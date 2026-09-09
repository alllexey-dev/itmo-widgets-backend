package dev.alllexey.itmowidgets.backend.dto

/** Catalog-only summary: received/skipped count wire rows, including nulls and duplicate IDs. */
data class SportCatalogUpdateResult(
    /** Accepted lesson IDs with known non-negative capacity; zero is a meaningful observation. */
    val capacities: Map<Long, Long>,
    val receivedLessons: Int,
    val newLessonsAdded: Int,
    /** Semantic lesson changes, excluding last-seen timestamps and dictionary-label-only changes. */
    val updatedLessons: Int,
    val skippedLessons: Int,
)
