package dev.alllexey.itmowidgets.backend.repositories

import dev.alllexey.itmowidgets.backend.model.FriendshipEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import java.util.UUID

interface FriendshipRepository : JpaRepository<FriendshipEntity, UUID> {
    @Query("""
        SELECT f FROM FriendshipEntity f
        WHERE (f.requester.isu = :firstIsu AND f.addressee.isu = :secondIsu)
           OR (f.requester.isu = :secondIsu AND f.addressee.isu = :firstIsu)
    """)
    fun findBetween(firstIsu: Int, secondIsu: Int): FriendshipEntity?

    @Query("""
        SELECT CASE WHEN f.requester.isu = :isu THEN f.addressee.isu ELSE f.requester.isu END
        FROM FriendshipEntity f
        WHERE f.status = 'ACCEPTED' AND (f.requester.isu = :isu OR f.addressee.isu = :isu)
        ORDER BY f.respondedAt DESC, f.id
    """)
    fun findUserFriendsIsu(isu: Int): List<Int>

    @Query("""
        SELECT f.requester.isu FROM FriendshipEntity f
        WHERE f.status = 'PENDING' AND f.addressee.isu = :isu
        ORDER BY f.createdAt DESC, f.id
    """)
    fun findIncomingRequests(isu: Int): List<Int>

    @Query("""
        SELECT f.addressee.isu FROM FriendshipEntity f
        WHERE f.status = 'PENDING' AND f.requester.isu = :isu
        ORDER BY f.createdAt DESC, f.id
    """)
    fun findOutgoingRequests(isu: Int): List<Int>

    fun countByStatus(status: FriendshipEntity.Status): Long

    @Query("""
        SELECT COUNT(f) FROM FriendshipEntity f
        WHERE f.status = 'ACCEPTED' AND (f.requester.id = :userId OR f.addressee.id = :userId)
    """)
    fun countFriendsOf(userId: UUID): Long
}
