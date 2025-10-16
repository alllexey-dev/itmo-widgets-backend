package me.alllexey123.itmowidgets.backend.services

import me.alllexey123.itmowidgets.backend.repositories.UserRepository
import org.springframework.security.core.userdetails.User
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.core.userdetails.UsernameNotFoundException
import org.springframework.stereotype.Service
import java.util.*

@Service
class UserDetailsServiceImpl(private val userRepository: UserRepository) : UserDetailsService {

    override fun loadUserByUsername(username: String): UserDetails {
        val userId = try {
            UUID.fromString(username)
        } catch (_: IllegalArgumentException) {
            throw UsernameNotFoundException("Invalid UUID format for user ID: $username")
        }

        return userRepository.findById(userId)
            .map { user -> User(user.id.toString(), "", emptyList()) }
            .orElseThrow { UsernameNotFoundException("User not found with ID: $username") }
    }
}