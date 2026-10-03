package dev.alllexey.itmowidgets.backend.feature.sport.service

import dev.alllexey.itmowidgets.backend.feature.sport.model.SportLesson
import dev.alllexey.itmowidgets.backend.feature.sport.persistence.SportLessonRepository
import dev.alllexey.itmowidgets.backend.platform.error.NotFoundException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional(readOnly = true)
class SportLessonService(private val sportLessonRepository: SportLessonRepository) {

    fun findLessonById(id: Long): SportLesson = sportLessonRepository.findById(id)
        .orElseThrow { NotFoundException("SportLesson not found with ID: $id") }

    fun findAll(): List<SportLesson> = sportLessonRepository.findAll()

    @Transactional
    fun save(sportLesson: SportLesson): SportLesson = sportLessonRepository.save(sportLesson)
}
