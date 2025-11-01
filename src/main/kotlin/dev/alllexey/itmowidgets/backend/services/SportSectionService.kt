package dev.alllexey.itmowidgets.backend.services

import dev.alllexey.itmowidgets.backend.model.SportSection
import dev.alllexey.itmowidgets.backend.repositories.SportSectionRepository
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class SportSectionService(private val sportSectionRepository: SportSectionRepository) {

    @Transactional
    fun get(id: Long): SportSection {
        return sportSectionRepository.findByIdOrNull(id)
            ?: throw RuntimeException("Sport section with id $id not found")
    }

    @Transactional
    fun findAll(): List<SportSection> {
        return sportSectionRepository.findAll()
    }

    @Transactional
    fun save(sportSection: SportSection): SportSection {
        return sportSectionRepository.save(sportSection)
    }
}