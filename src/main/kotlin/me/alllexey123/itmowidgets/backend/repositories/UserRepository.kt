package me.alllexey123.itmowidgets.backend.repositories

import me.alllexey123.itmowidgets.backend.model.User
import org.springframework.data.jpa.repository.JpaRepository
import java.util.*

interface UserRepository : JpaRepository<User, UUID> {

    fun findByIsu(isu: Int): User?
}