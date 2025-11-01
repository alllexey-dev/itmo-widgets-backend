package dev.alllexey.itmowidgets.backend.services

import dev.alllexey.itmowidgets.backend.model.SportBuilding
import dev.alllexey.itmowidgets.backend.repositories.SportBuildingRepository
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class SportBuildingService(private val sportBuildingRepository: SportBuildingRepository) {

    @Transactional
    fun get(id: Long): SportBuilding {
        return sportBuildingRepository.findByIdOrNull(id)
            ?: throw RuntimeException("Sport building with id $id not found")
    }

    @Transactional
    fun findAll(): List<SportBuilding> {
        return sportBuildingRepository.findAll()
    }

    @Transactional
    fun save(sportBuilding: SportBuilding): SportBuilding {
        return sportBuildingRepository.save(sportBuilding)
    }
}