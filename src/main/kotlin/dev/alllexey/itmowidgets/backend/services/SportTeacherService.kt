package dev.alllexey.itmowidgets.backend.services

import dev.alllexey.itmowidgets.backend.model.SportTeacher
import dev.alllexey.itmowidgets.backend.repositories.SportTeacherRepository
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class SportTeacherService(private val sportTeacherRepository: SportTeacherRepository) {

    @Transactional
    fun get(id: Long): SportTeacher {
        return sportTeacherRepository.findByIdOrNull(id)
            ?: throw RuntimeException("Sport teacher with id $id not found")
    }

    @Transactional
    fun findAll(): List<SportTeacher> {
        return sportTeacherRepository.findAll()
    }

    @Transactional
    fun save(sportTeacher: SportTeacher): SportTeacher {
        return sportTeacherRepository.save(sportTeacher)
    }
}