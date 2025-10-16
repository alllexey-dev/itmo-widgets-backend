package me.alllexey123.itmowidgets.backend.services

import me.alllexey123.itmowidgets.backend.model.User
import me.alllexey123.itmowidgets.backend.repositories.UserRepository
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