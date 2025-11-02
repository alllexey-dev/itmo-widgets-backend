package dev.alllexey.itmowidgets.backend.services

import dev.alllexey.itmowidgets.backend.exceptions.NotFoundException
import dev.alllexey.itmowidgets.backend.model.SportBuilding
import dev.alllexey.itmowidgets.backend.repositories.SportBuildingRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional(readOnly = true)
class SportBuildingService(private val sportBuildingRepository: SportBuildingRepository) {

    fun findBuildingById(id: Long): SportBuilding {
        return sportBuildingRepository.findById(id)
            .orElseThrow { NotFoundException("SportBuilding not found with ID: $id") }
    }

    fun findAll(): List<SportBuilding> {
        return sportBuildingRepository.findAll()
    }

    @Transactional
    fun save(sportBuilding: SportBuilding): SportBuilding {
        return sportBuildingRepository.save(sportBuilding)
    }
}