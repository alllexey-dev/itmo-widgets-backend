package dev.alllexey.itmowidgets.backend.feature.sport.service

import dev.alllexey.itmowidgets.backend.feature.app.service.AppSettingStore
import dev.alllexey.itmowidgets.backend.platform.error.InvalidRequestDataException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/**
 * The monthly auto-sign limit of every user: the `app_settings` key [LIMIT_KEY], [DEFAULT_LIMIT] while it is absent.
 * It replaces the retired per-user `user_settings.auto_sign_limit`, which Backend no longer reads.
 */
@Service
class SportAutoSignSettings(private val store: AppSettingStore) {
    /** [overridden] is true once an admin stored the value; [updatedAt] is when. */
    data class Limit(val limit: Int, val overridden: Boolean, val updatedAt: Instant?)

    /** A stored value that is not a number in [LIMIT_RANGE] (only SQL can write one) counts as the default. */
    @Transactional(readOnly = true)
    fun current(): Limit {
        val stored = store.find(LIMIT_KEY) ?: return Limit(DEFAULT_LIMIT, overridden = false, updatedAt = null)
        val limit = stored.value.toIntOrNull()?.takeIf { it in LIMIT_RANGE } ?: DEFAULT_LIMIT
        return Limit(limit, overridden = true, updatedAt = stored.updatedAt)
    }

    fun limit(): Int = current().limit

    /** Stores [limit] for everybody; the caller authorizes and audits. */
    @Transactional
    fun store(limit: Int, actorId: UUID, at: Instant) {
        if (limit !in LIMIT_RANGE) throw InvalidRequestDataException("Auto-sign limit must be in $LIMIT_RANGE")
        store.store(LIMIT_KEY, limit.toString(), actorId, at)
    }

    companion object {
        const val LIMIT_KEY = "sport.auto_sign_limit"
        const val DEFAULT_LIMIT = 10

        /** Zero turns new auto-sign entries off for everybody; existing entries stay queued. */
        val LIMIT_RANGE = 0..100
    }
}
