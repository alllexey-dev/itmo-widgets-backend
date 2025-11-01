package dev.alllexey.itmowidgets.backend.services

import dev.alllexey.itmowidgets.backend.model.SportTimeSlot
import dev.alllexey.itmowidgets.backend.repositories.SportTimeSlotRepository
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class SportTimeSlotService(private val sportTimeSlotRepository: SportTimeSlotRepository) {

    @Transactional
    fun get(id: Long): SportTimeSlot {
        return sportTimeSlotRepository.findByIdOrNull(id)
            ?: throw RuntimeException("Sport time slot with id $id not found")
    }

    @Transactional
    fun findAll(): List<SportTimeSlot> {
        return sportTimeSlotRepository.findAll()
    }

    @Transactional
    fun save(sportTimeSlot: SportTimeSlot): SportTimeSlot {
        return sportTimeSlotRepository.save(sportTimeSlot)
    }
}