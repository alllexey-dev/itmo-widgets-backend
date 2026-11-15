package dev.alllexey.itmowidgets.backend.feature.reviews.service

import dev.alllexey.itmowidgets.backend.feature.reviews.persistence.ExternalTeacherReviewRepository
import org.springframework.stereotype.Service

/**
 * The one way staff see a teacher's name, in the moderation queue and case, the audit and the AI summaries: the name of
 * an active Reviews copy, otherwise the official name with the bound of [TeacherNamesService.names] (at most 10
 * uncached My ITMO lookups per call). Call it after the transaction; nothing is stored.
 */
@Service
class TeacherDisplayNames(private val copies: ExternalTeacherReviewRepository, private val official: TeacherNamesService) {
    /** Absent when neither source knows the teacher. */
    fun of(isus: Collection<Int>): Map<Int, String> {
        if (isus.isEmpty()) return emptyMap()
        val fromCopies = copies.findActiveTeacherNames(ReviewsSyncStore.PROVIDER.name, isus.toSet())
            .associate { it.teacherIsu to it.teacherName }
        return fromCopies + official.names(isus.filterNot(fromCopies::containsKey))
    }
}
