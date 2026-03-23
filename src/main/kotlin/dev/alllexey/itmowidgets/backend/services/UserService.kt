package dev.alllexey.itmowidgets.backend.services

import dev.alllexey.itmowidgets.backend.exceptions.NotFoundException
import dev.alllexey.itmowidgets.backend.model.User
import dev.alllexey.itmowidgets.backend.repositories.UserRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Lazy
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.util.*

@Service
class UserService(private val userRepository: UserRepository) {

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

    fun findUserById(id: UUID): User {
        return userRepository.findById(id)
            .orElseThrow { NotFoundException("User not found with ID: $id") }
    }
}