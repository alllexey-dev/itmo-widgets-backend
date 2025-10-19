package dev.alllexey.itmowidgets.backend.services

import dev.alllexey.itmowidgets.backend.model.User
import dev.alllexey.itmowidgets.backend.repositories.UserRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class UserService(private val userRepository: UserRepository) {

    @Transactional
    fun findOrCreateByIsu(isu: Int): User {
        userRepository.findByIsu(isu)?.let {
            return it
        }

        return userRepository.save(User(isu = isu))
    }
}