package dev.alllexey.itmowidgets.backend.services

import dev.alllexey.itmowidgets.backend.exceptions.NotFoundException
import dev.alllexey.itmowidgets.backend.model.SportSection
import dev.alllexey.itmowidgets.backend.repositories.SportSectionRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional(readOnly = true)
class SportSectionService(private val sportSectionRepository: SportSectionRepository) {

    fun findSectionById(id: Long): SportSection {
        return sportSectionRepository.findById(id)
            .orElseThrow { NotFoundException("SportSection not found with ID: $id") }
    }

    fun findAll(): List<SportSection> {
        return sportSectionRepository.findAll()
    }

    @Transactional
    fun save(sportSection: SportSection): SportSection {
        return sportSectionRepository.save(sportSection)
    }
}