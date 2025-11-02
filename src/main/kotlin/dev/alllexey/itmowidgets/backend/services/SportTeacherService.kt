package dev.alllexey.itmowidgets.backend.services

import dev.alllexey.itmowidgets.backend.exceptions.NotFoundException
import dev.alllexey.itmowidgets.backend.model.SportTeacher
import dev.alllexey.itmowidgets.backend.repositories.SportTeacherRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional(readOnly = true)
class SportTeacherService(private val sportTeacherRepository: SportTeacherRepository) {

    fun findTeacherById(id: Long): SportTeacher {
        return sportTeacherRepository.findById(id)
            .orElseThrow { NotFoundException("SportTeacher not found with ID: $id") }
    }

    fun findAll(): List<SportTeacher> {
        return sportTeacherRepository.findAll()
    }

    @Transactional
    fun save(sportTeacher: SportTeacher): SportTeacher {
        return sportTeacherRepository.save(sportTeacher)
    }
}