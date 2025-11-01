package dev.alllexey.itmowidgets.backend.services

import dev.alllexey.itmowidgets.backend.model.SportBuilding
import dev.alllexey.itmowidgets.backend.model.SportSection
import dev.alllexey.itmowidgets.backend.model.SportTimeSlot
import org.springframework.context.ApplicationListener
import org.springframework.context.event.ContextRefreshedEvent
import org.springframework.core.annotation.Order
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Order(2)
class SportUpdateService(
    private val myItmoService: MyItmoService,
    private val sportTimeSlotService: SportTimeSlotService,
    private val sportBuildingService: SportBuildingService,
    private val sportSectionService: SportSectionService
) : ApplicationListener<ContextRefreshedEvent> {

    @Transactional
    override fun onApplicationEvent(event: ContextRefreshedEvent) {
        checkOtherUpdates()
        checkLessonUpdates()
    }

    @Transactional
    fun updateTimeSlots() {
        val apiTimeSlots = myItmoService.myItmo.api().sportTimeSlots.execute()
            .body()?.result ?: throw RuntimeException("Could not get sport time slots: result is empty")

        val dbTimeSlots = sportTimeSlotService.findAll()

        apiTimeSlots.filter { slot -> !dbTimeSlots.any { slot2 -> slot.id.toLong() == slot2.id } }
            .map { SportTimeSlot.fromApi(it) }
            .forEach { sportTimeSlotService.save(it) }
    }

    @Transactional
    fun updateFromFilters() {
        val apiFilters = myItmoService.myItmo.api().sportFilters.execute()
            .body()?.result ?: throw RuntimeException("Could not get sport filters: result is empty")

        val apiBuildings = apiFilters.buildingId
        val apiSections = apiFilters.sectionId

        val dbBuildings = sportBuildingService.findAll()
        val dbSections = sportSectionService.findAll()

        apiBuildings.filter { building -> !dbBuildings.any { building.id.toLong() == it.id } }
            .map { building -> SportBuilding.fromApi(building) }
            .forEach { sportBuildingService.save(it) }

        apiSections.filter { section -> !dbSections.any { section.id.toLong() == it.id } }
            .map { section -> SportSection.fromApi(section) }
            .forEach { sportSectionService.save(it) }
    }

    @Scheduled(cron = "0 0 * * * *")
    @Transactional
    fun checkOtherUpdates() {
        updateTimeSlots()
        updateFromFilters()
    }

    @Scheduled(cron = "0 */10 * * * *")
    fun checkLessonUpdates() {

    }

}