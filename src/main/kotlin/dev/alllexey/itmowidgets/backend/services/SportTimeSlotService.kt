package dev.alllexey.itmowidgets.backend.services

import dev.alllexey.itmowidgets.backend.exceptions.NotFoundException
import dev.alllexey.itmowidgets.backend.model.SportTimeSlot
import dev.alllexey.itmowidgets.backend.repositories.SportTimeSlotRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional(readOnly = true)
class SportTimeSlotService(private val sportTimeSlotRepository: SportTimeSlotRepository) {

    fun findTimeSlotById(id: Long): SportTimeSlot {
        return sportTimeSlotRepository.findById(id)
            .orElseThrow { NotFoundException("SportTimeSlot not found with ID: $id") }
    }

    fun findAll(): List<SportTimeSlot> {
        return sportTimeSlotRepository.findAll()
    }

    @Transactional
    fun save(sportTimeSlot: SportTimeSlot): SportTimeSlot {
        return sportTimeSlotRepository.save(sportTimeSlot)
    }
}