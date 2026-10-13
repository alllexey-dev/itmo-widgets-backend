package dev.alllexey.itmowidgets.backend.feature.sport.service

import org.springframework.boot.context.properties.ConfigurationProperties
import kotlin.math.ceil

/**
 * [capShare] of a lesson's places may be taken by section subscribers of its slot: they are queued for every
 * occurrence weeks ahead of anyone else, so the rest stays for students who book or queue one date at a time.
 */
@ConfigurationProperties("itmowidgets.sport.sections")
data class SportSectionConfig(val capShare: Double = 0.5) {
    init {
        require(capShare > 0 && capShare <= 1) { "Section cap share must be in (0, 1]" }
    }

    /** At least one subscriber for any lesson with places; zero places admit none. */
    fun cap(placesLimit: Int): Int = if (placesLimit <= 0) 0 else ceil(placesLimit * capShare).toInt().coerceAtLeast(1)
}
