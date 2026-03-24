package dev.alllexey.itmowidgets.backend.repositories

import dev.alllexey.itmowidgets.backend.model.FriendRequestEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository

@Repository
interface FriendRequestRepository : JpaRepository<FriendRequestEntity, Long> {

    @Query("""
    SELECT fr.to.isu
    FROM FriendRequestEntity fr
    WHERE fr.from.isu = :isu
      AND fr.status = 'ACTIVE'
      AND EXISTS (
          SELECT 1
          FROM FriendRequestEntity fr2
          WHERE fr2.from = fr.to
            AND fr2.status = 'ACTIVE'
            AND fr2.to = fr.from)
    """)
    fun findUserFriendsIsu(isu: Int): List<Int>

    @Query("""
    SELECT count(fr) > 0
    FROM FriendRequestEntity fr
    WHERE fr.from.isu = :isu1
      AND fr.status = 'ACTIVE'
      AND fr.to.isu = :isu2
      AND EXISTS (
          SELECT 1
          FROM FriendRequestEntity fr2
          WHERE fr2.from.isu = :isu2
            AND fr2.status = 'ACTIVE'
            AND fr2.to.isu = :isu1)
    """)
    fun areFriends(isu1: Int, isu2: Int): Boolean

    fun findByFromIsuAndToIsu(fromIsu: Int, toIsu: Int): FriendRequestEntity?

    @Query("""
    SELECT fr.from.isu
    FROM FriendRequestEntity fr
    WHERE fr.to.isu = :isu
      AND fr.status = 'ACTIVE'
      AND NOT EXISTS (
          SELECT 1
          FROM FriendRequestEntity fr2
          WHERE fr2.from = fr.to
            AND fr2.status = 'ACTIVE'
            AND fr2.to = fr.from)
    """)
    fun findIncomingRequests(isu: Int): List<Int>

    @Query("""
    SELECT fr.to.isu
    FROM FriendRequestEntity fr
    WHERE fr.from.isu = :isu
      AND fr.status = 'ACTIVE'
      AND NOT EXISTS (
          SELECT 1
          FROM FriendRequestEntity fr2
          WHERE fr2.from = fr.to
            AND fr2.status = 'ACTIVE'
            AND fr2.to = fr.from)
    """)
    fun findOutgoingRequests(isu: Int): List<Int>
}