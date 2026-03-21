package dev.alllexey.itmowidgets.backend.services

import dev.alllexey.itmowidgets.backend.exceptions.NotFoundException
import dev.alllexey.itmowidgets.backend.model.User
import dev.alllexey.itmowidgets.backend.repositories.UserRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.*

@Service
class UserService(private val userRepository: UserRepository) {

    @Transactional
    fun findOrCreateByIsu(isu: Int): User {
        userRepository.insertIgnore(UUID.randomUUID(), isu)
        return userRepository.findByIsu(isu) ?: throw IllegalStateException("User creation failed")
    }

    fun findUserById(id: UUID): User {
        return userRepository.findById(id)
            .orElseThrow { NotFoundException("User not found with ID: $id") }
    }
}