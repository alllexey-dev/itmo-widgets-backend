package dev.alllexey.itmowidgets.backend.repositories

import dev.alllexey.itmowidgets.backend.model.MyItmoStorage
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query

interface MyItmoRepository : JpaRepository<MyItmoStorage, Long> {
    @Modifying
    @Query(
        value = """
            INSERT INTO my_itmo_storage (id, access_token_expires_at, refresh_token_expires_at)
            VALUES (1, 0, 0)
            ON CONFLICT (id) DO NOTHING
        """,
        nativeQuery = true,
    )
    fun insertSingletonIfAbsent(): Int

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM MyItmoStorage s WHERE s.id = 1")
    fun getWithLock(): MyItmoStorage?
}
