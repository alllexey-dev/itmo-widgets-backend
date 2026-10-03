package dev.alllexey.itmowidgets.backend.feature.reviews.service

/** A Reviews teacher id in the ISU range is the ISU number; smaller ids are the provider's own teachers without ISU. */
object ReviewTeachers {
    const val SITE = "https://onetwozzzplus.github.io/reviews/"
    const val ISU_MIN = 100_000
    const val ISU_MAX = 9_999_999

    fun siteUrl(isu: Int): String = "$SITE#/teacher/$isu"

    fun isIsu(externalTeacherId: Long): Boolean = externalTeacherId in ISU_MIN.toLong()..ISU_MAX.toLong()
}
