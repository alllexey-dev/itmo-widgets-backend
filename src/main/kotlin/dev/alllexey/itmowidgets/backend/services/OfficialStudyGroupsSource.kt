package dev.alllexey.itmowidgets.backend.services

import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Cache education only, never contacts or the complete directory profile. */
data class OfficialStudyGroup(val name: String, val course: Int, val facultyName: String)

fun interface OfficialStudyGroupsSource {
    fun load(isu: Int): List<OfficialStudyGroup>
}

class StudyGroupsUnavailable(val temporary: Boolean) : RuntimeException("Study groups unavailable")

@Service
class MyItmoStudyGroupsSource(private val myItmoService: MyItmoService) : OfficialStudyGroupsSource {
    override fun load(isu: Int): List<OfficialStudyGroup> {
        check(!TransactionSynchronizationManager.isActualTransactionActive()) { "Directory I/O requires no transaction" }
        val response = try {
            val call = myItmoService.myItmo.api.getPersonality(isu)
            call.timeout().timeout(3, TimeUnit.SECONDS)
            call.execute()
        } catch (_: IOException) {
            throw StudyGroupsUnavailable(temporary = true)
        } catch (_: RuntimeException) {
            throw StudyGroupsUnavailable(temporary = true)
        }
        if (!response.isSuccessful) throw StudyGroupsUnavailable(temporary = response.code() >= 500 || response.code() == 429)
        val body = response.body() ?: throw StudyGroupsUnavailable(temporary = false)
        if (body.errorCode != 0) throw StudyGroupsUnavailable(temporary = false)
        val profile = body.result ?: throw StudyGroupsUnavailable(temporary = false)
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
