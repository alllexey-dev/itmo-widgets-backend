package dev.alllexey.itmowidgets.backend.services

import dev.alllexey.itmowidgets.backend.exceptions.BusinessRuleException
import dev.alllexey.itmowidgets.backend.exceptions.NotFoundException
import dev.alllexey.itmowidgets.backend.model.User
import dev.alllexey.itmowidgets.backend.repositories.UserRepository
import dev.alllexey.itmowidgets.backend.services.ItmoJwtVerifier.Companion.getClaimOrNull
import dev.alllexey.itmowidgets.backend.services.ItmoJwtVerifier.Companion.getIsu
import dev.alllexey.itmowidgets.core.model.UserSettings
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Lazy
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.util.*

@Service
class UserService(
    private val userRepository: UserRepository,
    private val itmoJwtVerifier: ItmoJwtVerifier
) {

    @Lazy
    @Autowired
    lateinit var userService: UserService

    @Transactional
    fun findOrCreateByIsu(isu: Int): User {
        val user = userRepository.findByIsu(isu)
        if (user != null) return user
        return userService.createUser(isu)
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Synchronized
    fun createUser(isu: Int): User {
        val user = userRepository.findByIsu(isu)
        if (user != null) return user
        val id = UUID.randomUUID()
        userRepository.insertSettingsIgnore(id)
        userRepository.insertIgnore(id, isu, id)
        return userRepository.findById(id).orElseThrow { RuntimeException("User creation failed") }
    }

    @Transactional
    fun updateSettings(user: User, userSettings: UserSettings) {
        user.settings.apply {
            sportLogging = userSettings.sportLogging
            scheduleLogging = userSettings.scheduleLogging
        }
    }

    @Transactional
    fun updateDataFromIdToken(user: User, idToken: String) {
        val token = itmoJwtVerifier.verifyAndDecode(idToken)
        val isu = token.getIsu() ?: throw BusinessRuleException("Not found isu in idToken")
        if (user.isu != isu) throw BusinessRuleException("User isu didn't match isu in idToken")
        user.pictureUrl = token.getClaimOrNull("picture")?.asString()
        user.name = token.getClaimOrNull("name")?.asString()
    }

    fun findUserByIsu(isu: Int): User {
        return userRepository.findByIsu(isu)
            ?: throw NotFoundException("User not found with isu: $isu")
    }

    fun findUserById(id: UUID): User {
        return userRepository.findById(id)
            .orElseThrow { NotFoundException("User not found with ID: $id") }
    }
}