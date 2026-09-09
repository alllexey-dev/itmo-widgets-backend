package dev.alllexey.itmowidgets.backend.services

import dev.alllexey.itmowidgets.backend.model.User
import dev.alllexey.itmowidgets.backend.repositories.UserRepository
import java.util.UUID
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

@Service
class UserRegistrationService(private val userRepository: UserRepository) {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun findOrCreateByIsu(isu: Int): User {
        userRepository.insertIgnore(UUID.randomUUID(), isu)
        // A competing registration may own this ISU. Resolve its ID before creating settings,
        // without loading the inverse eager relation until the settings row exists.
        val id = checkNotNull(userRepository.findIdByIsu(isu)) { "User registration did not resolve an identity" }
        userRepository.insertSettingsIgnore(id)
        return userRepository.findById(id).orElseThrow { IllegalStateException("Registered user not found") }
    }
}
