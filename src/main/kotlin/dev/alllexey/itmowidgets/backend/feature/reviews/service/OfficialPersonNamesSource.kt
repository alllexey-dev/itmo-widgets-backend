package dev.alllexey.itmowidgets.backend.feature.reviews.service

import dev.alllexey.itmowidgets.backend.feature.credentials.service.MyItmoGateway
import dev.alllexey.itmowidgets.backend.feature.credentials.service.MyItmoResult
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionSynchronizationManager

/** The full name of an ISU person from the official directory; null when the directory has none for it. */
fun interface OfficialPersonNamesSource {
    fun name(isu: Int): String?
}

class PersonNameUnavailable(val temporary: Boolean) : RuntimeException("Person name unavailable")

/** Reads only `fio` of the MyITMO personality, for moderators; the name is never stored. */
@Service
class MyItmoPersonNamesSource(private val myItmo: MyItmoGateway) : OfficialPersonNamesSource {
    override fun name(isu: Int): String? {
        check(!TransactionSynchronizationManager.isActualTransactionActive()) { "Directory I/O requires no transaction" }
        val result = try {
            myItmo.personality(isu)
        } catch (_: Exception) {
            throw PersonNameUnavailable(temporary = true)
        }
        val profile = when (result) {
            is MyItmoResult.Success -> result.value
            is MyItmoResult.Failure -> throw PersonNameUnavailable(result.temporary)
        }
        if (profile.isu != isu.toLong()) return null
        return profile.fio?.trim()?.takeIf(String::isNotEmpty)
    }
}
