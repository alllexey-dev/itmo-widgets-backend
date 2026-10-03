package dev.alllexey.itmowidgets.backend.feature.reviews.service

import dev.alllexey.itmowidgets.backend.feature.reviews.model.IsuPotokEntity
import dev.alllexey.itmowidgets.backend.feature.reviews.model.IsuPotokMemberEntity
import dev.alllexey.itmowidgets.backend.feature.reviews.model.IsuPotokMemberId
import dev.alllexey.itmowidgets.backend.feature.reviews.model.IsuPotokTeacherEntity
import dev.alllexey.itmowidgets.backend.feature.reviews.model.IsuPotokTeacherId
import dev.alllexey.itmowidgets.backend.feature.reviews.persistence.IsuPotokMemberRepository
import dev.alllexey.itmowidgets.backend.feature.reviews.persistence.IsuPotokRepository
import dev.alllexey.itmowidgets.backend.feature.reviews.persistence.IsuPotokTeacherRepository
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant

/**
 * ISU flow lists by flow number: teachers stay fresh for 30 days, members for one day. Only numbers are
 * stored. Every call commits on its own; the ISU requests themselves run outside transactions.
 */
@Service
@Transactional(propagation = Propagation.REQUIRES_NEW)
class IsuPotokCache(
    private val potoks: IsuPotokRepository,
    private val teachers: IsuPotokTeacherRepository,
    private val members: IsuPotokMemberRepository,
) {
    @PersistenceContext
    private lateinit var em: EntityManager

    /** Null when the teachers of the flow are unknown or older than [TEACHERS_TTL]. */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    fun teachers(potokId: Long, now: Instant): Set<Int>? {
        val checkedAt = potoks.findById(potokId).orElse(null)?.teachersCheckedAt ?: return null
        if (checkedAt < now.minus(TEACHERS_TTL)) return null
        return teachers.findTeachers(potokId).toSet()
    }

    fun saveTeachers(potokId: Long, teacherIsus: Set<Int>, now: Instant) {
        val potok = potok(potokId)
        teachers.deleteAllByIdPotokId(potokId)
        teacherIsus.forEach { em.persist(IsuPotokTeacherEntity(IsuPotokTeacherId(potokId, it))) }
        potok.teachersCheckedAt = now
    }

    /** Null when the members of the flow are unknown or older than [MEMBERS_TTL]. */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    fun isMember(potokId: Long, isu: Int, now: Instant): Boolean? {
        val checkedAt = potoks.findById(potokId).orElse(null)?.membersCheckedAt ?: return null
        if (checkedAt < now.minus(MEMBERS_TTL)) return null
        return members.existsById(IsuPotokMemberId(potokId, isu))
    }

    fun saveMembers(potokId: Long, memberIsus: Set<Int>, now: Instant) {
        val potok = potok(potokId)
        members.deleteAllByIdPotokId(potokId)
        memberIsus.forEach { em.persist(IsuPotokMemberEntity(IsuPotokMemberId(potokId, it))) }
        potok.membersCheckedAt = now
    }

    /** Drops stale member lists, then flows that keep neither fresh teachers nor members. */
    fun purge(now: Instant) {
        val membersCutoff = now.minus(MEMBERS_TTL)
        members.deleteCheckedBefore(membersCutoff)
        potoks.forgetMembersCheckedBefore(membersCutoff)
        potoks.deleteStale(now.minus(TEACHERS_TTL))
    }

    private fun potok(potokId: Long): IsuPotokEntity = potoks.findById(potokId).orElse(null) ?: IsuPotokEntity(potokId).also {
        em.persist(it)
        em.flush()
    }

    companion object {
        val TEACHERS_TTL: Duration = Duration.ofDays(30)
        val MEMBERS_TTL: Duration = Duration.ofDays(1)
    }
}
