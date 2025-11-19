package dev.alllexey.itmowidgets.backend.services

import dev.alllexey.itmowidgets.backend.exceptions.NotFoundException
import dev.alllexey.itmowidgets.backend.model.SportLesson
import dev.alllexey.itmowidgets.backend.repositories.SportLessonRepository
import dev.alllexey.itmowidgets.core.model.BasicSportLessonData
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional(readOnly = true)
class SportLessonService(private val sportLessonRepository: SportLessonRepository) {

    fun findLessonById(id: Long): SportLesson {
        return sportLessonRepository.findById(id)
            .orElseThrow { NotFoundException("SportLesson not found with ID: $id") }
    }

    fun findAll(): List<SportLesson> {
        return sportLessonRepository.findAll()
    }

    @Transactional
    fun save(sportLesson: SportLesson): SportLesson {
        return sportLessonRepository.save(sportLesson)
    }

    fun toBasicData(sportLesson: SportLesson): BasicSportLessonData {
        return BasicSportLessonData(
            id = sportLesson.id,
            sectionId = sportLesson.section.id,
            sectionName = sportLesson.sectionName,
            sectionLevel = sportLesson.sectionLevel,
            buildingId = sportLesson.building.id,
            roomName = sportLesson.roomName,
            dateStart = sportLesson.start,
            dateEnd = sportLesson.end,
            timeSlotId = sportLesson.timeSlot.id,
            teacherIsu = sportLesson.teacher.isu,
            teacherFio = sportLesson.teacher.name
        )
    }
}