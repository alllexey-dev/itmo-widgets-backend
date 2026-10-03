package dev.alllexey.itmowidgets.backend.feature.users.service

import dev.alllexey.itmowidgets.backend.feature.credentials.service.MyItmoGateway
import dev.alllexey.itmowidgets.backend.feature.credentials.service.MyItmoResult
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionSynchronizationManager

/** Cache education only, never contacts or the complete directory profile. */
data class OfficialStudyGroup(val name: String, val course: Int, val facultyName: String)

fun interface OfficialStudyGroupsSource {
    fun load(isu: Int): List<OfficialStudyGroup>
}

class StudyGroupsUnavailable(val temporary: Boolean) : RuntimeException("Study groups unavailable")

@Service
class MyItmoStudyGroupsSource(private val myItmo: MyItmoGateway) : OfficialStudyGroupsSource {
    override fun load(isu: Int): List<OfficialStudyGroup> {
        check(!TransactionSynchronizationManager.isActualTransactionActive()) { "Directory I/O requires no transaction" }
        val result = try {
            myItmo.personality(isu)
        } catch (_: Exception) {
            throw StudyGroupsUnavailable(temporary = true)
        }
        val profile = when (result) {
            is MyItmoResult.Success -> result.value
            is MyItmoResult.Failure -> throw StudyGroupsUnavailable(result.temporary)
        }
        if (profile.isu != isu.toLong()) throw StudyGroupsUnavailable(temporary = false)
        val education = profile.education ?: throw StudyGroupsUnavailable(temporary = false)
        return education.map { entry ->
            val name = entry?.group?.trim()?.takeIf(String::isNotEmpty)
                ?: throw StudyGroupsUnavailable(temporary = false)
            val course = entry.course?.trim()?.toIntOrNull()?.takeIf { it > 0 }
                ?: throw StudyGroupsUnavailable(temporary = false)
            val faculty = entry.facultyName?.trim()?.takeIf(String::isNotEmpty)
                ?: throw StudyGroupsUnavailable(temporary = false)
            OfficialStudyGroup(name, course, faculty)
        }.distinct()
    }
}
