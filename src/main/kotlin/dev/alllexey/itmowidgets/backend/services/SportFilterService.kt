package dev.alllexey.itmowidgets.backend.services

import dev.alllexey.itmowidgets.backend.exceptions.*
import dev.alllexey.itmowidgets.backend.model.SportFilter
import dev.alllexey.itmowidgets.backend.repositories.*
import dev.alllexey.itmowidgets.core.model.SportFilterRequest
import dev.alllexey.itmowidgets.core.model.SportFilterResponse
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
@Transactional(readOnly = true)
class SportFilterService(
    private val timeSlotRepository: SportTimeSlotRepository,
    private val sportSectionRepository: SportSectionRepository,
    private val sportBuildingRepository: SportBuildingRepository,
    private val sportTeacherRepository: SportTeacherRepository,
    private val sportFilterRepository: SportFilterRepository,
    private val userService: UserService
) {

    @Transactional
    fun createFilterForUser(userId: UUID, request: SportFilterRequest): SportFilter {
        val user = userService.findUserById(userId)

        if (user.sportFilters.size >= 10) {
            throw BusinessRuleException("Sports filters count cannot exceed 10.")
        }

        val newFilter = SportFilter(user = user)
        populateFilterFromRequest(newFilter, request)
        return sportFilterRepository.save(newFilter)
    }

    @Transactional
    fun editFilterForUser(userId: UUID, filterId: Long, request: SportFilterRequest): SportFilter {
        val user = userService.findUserById(userId)
        val filter = findById(filterId)

        if (filter.user.id != user.id) {
            throw PermissionDeniedException("You do not have permission to edit this filter.")
        }

        populateFilterFromRequest(filter, request)
        return sportFilterRepository.save(filter)
    }

    @Transactional
    fun deleteFilterForUser(userId: UUID, filterId: Long) {
        val user = userService.findUserById(userId)
        val filter = findById(filterId)

        if (filter.user.id != user.id) {
            throw PermissionDeniedException("You do not have permission to delete this filter.")
        }

        sportFilterRepository.delete(filter)
    }

    @Transactional
    fun deleteAllFiltersForUser(userId: UUID) {
        userService.findUserById(userId).sportFilters.clear()
    }

    fun findById(filterId: Long): SportFilter {
        return sportFilterRepository.findById(filterId)
            .orElseThrow { NotFoundException("Filter not found with ID: $filterId") }
    }

    fun findAllForUser(userId: UUID): List<SportFilter> {
        return sportFilterRepository.findAllByUserIdWithDetails(userId)
    }

    fun getAllowedBuildingIds(): List<Long> {
        return sportBuildingRepository.findAll().map { it.id }
    }

    private fun populateFilterFromRequest(sportFilter: SportFilter, request: SportFilterRequest) {
        val sectionIds = request.sectionIds.distinct()
        val buildingIds = request.buildingIds.distinct()
        val timeSlotIds = request.timeSlotIds.distinct()
        val teacherIds = request.teacherIds.distinct()

        val sections = sportSectionRepository.findAllById(sectionIds)
        val buildings = sportBuildingRepository.findAllById(buildingIds)
        val timeSlots = timeSlotRepository.findAllById(timeSlotIds)
        val teachers = sportTeacherRepository.findAllById(teacherIds)

        validateEntitiesExist("sections", sectionIds, sections.map { it.id })
        validateEntitiesExist("buildings", buildingIds, buildings.map { it.id })
        validateEntitiesExist("timeSlots", timeSlotIds, timeSlots.map { it.id })
        validateEntitiesExist("teachers", teacherIds, teachers.map { it.isu })

        sportFilter.sections = sections.toMutableSet()
        sportFilter.buildings = buildings.toMutableSet()
        sportFilter.teachers = teachers.toMutableSet()
        sportFilter.timeSlots = timeSlots.toMutableSet()
    }

    private fun validateEntitiesExist(entityName: String, requestedIds: List<Long>, foundIds: List<Long>) {
        if (requestedIds.size != foundIds.size) {
            val missingIds = requestedIds.toSet() - foundIds.toSet()
            throw InvalidRequestDataException("Invalid request: unknown IDs for $entityName: $missingIds")
        }
    }

    fun toResponse(sportFilter: SportFilter): SportFilterResponse {
        return SportFilterResponse(
            id = sportFilter.id!!,
            sectionIds = sportFilter.sections.map { it.id },
            buildingIds = sportFilter.buildings.map { it.id },
            timeSlotIds = sportFilter.timeSlots.map { it.id },
            teacherIds = sportFilter.teachers.map { it.isu },
        )
    }
}
