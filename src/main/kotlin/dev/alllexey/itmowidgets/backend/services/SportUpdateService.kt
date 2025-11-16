package dev.alllexey.itmowidgets.backend.services

import dev.alllexey.itmowidgets.backend.model.*
import dev.alllexey.itmowidgets.backend.repositories.SportFreeSignEntryRepository
import dev.alllexey.itmowidgets.backend.repositories.SportLessonRepository
import dev.alllexey.itmowidgets.backend.repositories.SportUpdateLogRepository
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationListener
import org.springframework.context.event.ContextRefreshedEvent
import org.springframework.core.annotation.Order
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.time.OffsetDateTime

@Service
@Order(2)
class SportUpdateService(
    private val myItmoService: MyItmoService,
    private val sportTimeSlotService: SportTimeSlotService,
    private val sportBuildingService: SportBuildingService,
    private val sportSectionService: SportSectionService,
    private val sportLessonRepository: SportLessonRepository,
    private val sportUpdateLogRepository: SportUpdateLogRepository,
    private val sportTeacherService: SportTeacherService,
    private val sportFilterNotificationService: SportFilterNotificationService,
    private val sportFreeSignEntryRepository: SportFreeSignEntryRepository,
    private val sportFreeSignNotificationService: SportFreeSignNotificationService
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

        apiTimeSlots.filter { slot -> !dbTimeSlots.any { slot2 -> slot.id == slot2.id } }
            .map { SportTimeSlot.fromApi(it) }
            .forEach { sportTimeSlotService.save(it) }
    }

    @Transactional
    fun updateFromFilters() {
        val apiFilters = myItmoService.myItmo.api().sportFilters.execute()
            .body()?.result ?: throw RuntimeException("Could not get sport filters: result is empty")

        val apiBuildings = apiFilters.buildingId
        val apiSections = apiFilters.sectionId
        val apiTeachers = apiFilters.teacherIsu

        val dbBuildings = sportBuildingService.findAll()
        val dbSections = sportSectionService.findAll()
        val dbTeachers = sportTeacherService.findAll()

        apiBuildings.filter { building -> !dbBuildings.any { building.id == it.id } }
            .map { building -> SportBuilding.fromApi(building) }
            .forEach { sportBuildingService.save(it) }

        apiSections.filter { section -> !dbSections.any { section.id == it.id } }
            .map { section -> SportSection.fromApi(section) }
            .forEach { sportSectionService.save(it) }

        apiTeachers.filter { teacher -> !dbTeachers.any { teacher.id == it.isu } }
            .map { teacher -> SportTeacher.fromApi(teacher) }
            .forEach { sportTeacherService.save(it) }
    }

    @Scheduled(cron = "0 0 * * * *")
    @Transactional
    fun checkOtherUpdates() {
        updateTimeSlots()
        updateFromFilters()
    }

    @Scheduled(cron = "0 */10 * * * *")
    @Transactional
    fun checkLessonUpdates() {
        val apiLessons = myItmoService.myItmo.api().getSportSchedule(
            LocalDate.now().plusDays(1),
            LocalDate.now().plusDays(21),
            null,
            null,
            null,
        ).execute().body()?.result?.flatMap { it.lessons ?: emptyList() }
            ?: throw RuntimeException("Could not get sport schedule: result is empty")

        val dbLessonIds = sportLessonRepository.findAllIds()
        val newApiLessons = apiLessons.filter { it.id !in dbLessonIds }

        if (newApiLessons.isEmpty()) {
            sportUpdateLogRepository.save(SportUpdateLog(newLessonsAdded = 0))
            return
        }

        val sections = sportSectionService.findAll()
        val buildings = sportBuildingService.findAll()
        val teachers = sportTeacherService.findAll()
        val timeSlots = sportTimeSlotService.findAll()
        val sectionsMap = sections.associateBy { it.id }
        val buildingsMap = buildings.associateBy { it.id }
        val teacherMap = teachers.associateBy { it.isu }
        val timeSlotMap = timeSlots.associateBy { it.id }

        val mappedLessons = mutableListOf<SportLesson>()
        newApiLessons.forEach { apiLesson ->
            try {
                val lesson = SportLesson(
                    id = apiLesson.id,
                    section = sectionsMap[apiLesson.sectionId]
                        ?: throw RuntimeException("Could not get section for lesson: ${apiLesson.id}"),
                    timeSlot = timeSlotMap[apiLesson.timeSlotId]
                        ?: throw RuntimeException("Could not get time slot for lesson: ${apiLesson.id}"),
                    building = buildingsMap[apiLesson.buildingId]
                        ?: buildingsMap[0]
                        ?: throw RuntimeException("Could not get building for lesson: ${apiLesson.id}"),
                    teacher = teacherMap[apiLesson.teacherIsu]
                        ?: throw RuntimeException("Could not get teacher for lesson: ${apiLesson.id}"),
                    roomId = apiLesson.roomId,
                    roomName = apiLesson.roomName,
                    start = apiLesson.date,
                    end = apiLesson.dateEnd,
                )

                mappedLessons.add(lesson)
            } catch (e: Exception) {
                logger.error("Could not map api lesson", e)
                logger.error(apiLesson.toString())
            }
        }

        val log = SportUpdateLog(
            newLessonsAdded = mappedLessons.size,
            newLessons = mappedLessons
        )

        mappedLessons.forEach { it.sportUpdateLog = log }
        val savedLog = sportUpdateLogRepository.save(log)

        sportFilterNotificationService.sendNotificationsForNewLessons(savedLog.newLessons)
    }


    @Scheduled(fixedRate = 60 * 1000) // every minute
    @Transactional
    fun processSportLimits() {
        val limits = myItmoService.myItmo.api().sportSignLimits.execute().body()?.result
            ?: throw RuntimeException("Could not get sport limits: result is empty")

        val map = limits.flatMap { it.value.entries }.associate { it.key to it.value }
        sportFreeSignNotificationService.sendNotificationsForFreeLessons(map)
    }

    @Scheduled(cron = "0 0 1 * * ?")
    @Transactional
    fun cleanupExpiredEntries() {
        val expiredEntries = sportFreeSignEntryRepository.findExpiredEntries(OffsetDateTime.now())
        expiredEntries.forEach { it.status = FreeSignEntryStatus.EXPIRED }
        sportFreeSignEntryRepository.saveAll(expiredEntries)
    }

    companion object {
        private val logger = LoggerFactory.getLogger(SportUpdateService::class.java)
    }
}