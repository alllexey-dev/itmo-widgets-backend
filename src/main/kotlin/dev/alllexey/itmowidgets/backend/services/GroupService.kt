package dev.alllexey.itmowidgets.backend.services

import com.auth0.jwt.interfaces.DecodedJWT
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import dev.alllexey.itmowidgets.backend.repositories.FacultyRepository
import dev.alllexey.itmowidgets.backend.repositories.GroupRepository
import dev.alllexey.itmowidgets.backend.repositories.QualificationRepository
import dev.alllexey.itmowidgets.backend.services.ItmoJwtVerifier.Companion.getClaimOrNull
import jakarta.persistence.EntityManager
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.retry.annotation.Backoff
import org.springframework.retry.annotation.Retryable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.util.*

@Service
class GroupService(
    private val facultyRepository: FacultyRepository,
    private val qualificationRepository: QualificationRepository,
    private val groupRepository: GroupRepository,
    private val objectMapper: ObjectMapper,
    private val entityManager: EntityManager
) {

    @Retryable(
        retryFor = [
            Exception::class
        ],
        maxAttempts = 5,
        backoff = Backoff(delay = 50)
    )
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun groupIdsByIdToken(jwt: DecodedJWT): List<UUID> {
        val groupsRaw = try {
            val json = jwt.getClaimOrNull("groups")?.toString()
                ?: return emptyList()

            objectMapper.readValue(
                json,
                object : TypeReference<List<Group>>() {}
            )
        } catch (e: Exception) {
            logger.error("Error parsing groups", e)
            return emptyList()
        }

        groupsRaw.forEach { raw ->
            facultyRepository.upsert(
                raw.faculty.id,
                raw.faculty.name,
                raw.faculty.short_name
            )

            qualificationRepository.upsert(
                raw.qualification.code,
                raw.qualification.name
            )
        }

        val ids = groupsRaw.map { raw ->
            val stableId = UUID.nameUUIDFromBytes(raw.name.toByteArray())

            groupRepository.upsert(
                id = stableId,
                name = raw.name,
                course = raw.course,
                facultyId = raw.faculty.id,
                qualificationCode = raw.qualification.code
            )

            stableId
        }

        entityManager.flush()
        return ids
    }

    data class Group(
        val qualification: Qualification,
        val name: String,
        val course: Int,
        val faculty: Faculty
    )

    data class Qualification(
        val code: Long,
        val name: String,
    )

    data class Faculty(
        val name: String,
        val short_name: String,
        val id: Long,
    )

    companion object {
        val logger: Logger = LoggerFactory.getLogger(GroupService::class.java)
    }
}
