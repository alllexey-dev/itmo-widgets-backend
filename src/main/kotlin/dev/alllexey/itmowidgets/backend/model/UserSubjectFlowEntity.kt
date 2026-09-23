package dev.alllexey.itmowidgets.backend.model

import jakarta.persistence.*
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.LocalDate
import java.util.UUID

@Embeddable
data class UserSubjectFlowId(
    @Column(nullable = false) val userId: UUID,
    @Column(nullable = false) val subjectId: Long,
    @Column(nullable = false, length = 8) val periodKey: String,
    @Column(nullable = false) val flowId: Long,
) : java.io.Serializable

/** A schedule flow seen in the user's uploaded lessons; kept after the lessons are gone. */
@Entity
@Table(name = "user_subject_flows")
class UserSubjectFlowEntity(
    @EmbeddedId val id: UserSubjectFlowId,
    @Column(nullable = false, columnDefinition = "text") val groupName: String,
    @Column(nullable = false) val typeId: Int,
    @JdbcTypeCode(SqlTypes.LOCAL_DATE)
    @Column(nullable = false) val lastSeen: LocalDate,
)
