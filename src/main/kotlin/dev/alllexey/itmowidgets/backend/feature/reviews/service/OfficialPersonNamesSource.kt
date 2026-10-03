package dev.alllexey.itmowidgets.backend.feature.reviews.service

import dev.alllexey.itmowidgets.backend.feature.credentials.service.MyItmoService
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.io.IOException
import java.util.concurrent.TimeUnit

/** The full name of an ISU person from the official directory; null when the directory has none for it. */
fun interface OfficialPersonNamesSource {
    fun name(isu: Int): String?
}

class PersonNameUnavailable(val temporary: Boolean) : RuntimeException("Person name unavailable")

/** Reads only `fio` of the MyITMO personality, for moderators; the name is never stored. */
@Service
class MyItmoPersonNamesSource(private val myItmoService: MyItmoService) : OfficialPersonNamesSource {
    override fun name(isu: Int): String? {
        check(!TransactionSynchronizationManager.isActualTransactionActive()) { "Directory I/O requires no transaction" }
        val response = try {
            val call = myItmoService.myItmo.api.getPersonality(isu)
            call.timeout().timeout(3, TimeUnit.SECONDS)
            call.execute()
        } catch (_: IOException) {
            throw PersonNameUnavailable(temporary = true)
        } catch (_: RuntimeException) {
            throw PersonNameUnavailable(temporary = true)
        }
        if (!response.isSuccessful) throw PersonNameUnavailable(temporary = response.code() >= 500 || response.code() == 429)
        val body = response.body() ?: throw PersonNameUnavailable(temporary = false)
        if (body.errorCode != 0) throw PersonNameUnavailable(temporary = false)
        val profile = body.result ?: throw PersonNameUnavailable(temporary = false)
        if (profile.isu != isu.toLong()) return null
        return profile.fio?.trim()?.takeIf(String::isNotEmpty)
    }
}
