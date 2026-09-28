package dev.alllexey.itmowidgets.backend.repositories

import dev.alllexey.itmowidgets.backend.model.ServiceCredentialEntity
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query

interface ServiceCredentialRepository : JpaRepository<ServiceCredentialEntity, String> {
    /** Locks in key order, the same for every caller, so concurrent writers cannot deadlock. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM ServiceCredentialEntity c WHERE c.key IN :keys ORDER BY c.key")
    fun lockAll(keys: Collection<String>): List<ServiceCredentialEntity>
}
