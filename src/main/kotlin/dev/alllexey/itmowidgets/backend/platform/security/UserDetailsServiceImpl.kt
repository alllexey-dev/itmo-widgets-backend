package dev.alllexey.itmowidgets.backend.platform.security

import dev.alllexey.itmowidgets.backend.feature.users.persistence.UserRepository
import org.springframework.security.core.Authentication
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
            .map { user -> principal(user.id) }
            .orElseThrow { UsernameNotFoundException("User not found with ID: $username") }
    }

    companion object {
        /** The principal of a user id that is known to exist; [uuid] reads the id back. */
        fun principal(userId: UUID): UserDetails = User(userId.toString(), "", emptyList())

        fun Authentication.uuid(): UUID = UUID.fromString(this.name)
    }
}
