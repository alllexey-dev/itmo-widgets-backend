package dev.alllexey.itmowidgets.backend.feature.users.service

import dev.alllexey.itmowidgets.backend.feature.users.model.User
import dev.alllexey.itmowidgets.backend.feature.users.persistence.UserRepository
import dev.alllexey.itmowidgets.backend.feature.users.web.UserPrivacySettings
import dev.alllexey.itmowidgets.backend.platform.error.BusinessRuleException
import dev.alllexey.itmowidgets.backend.platform.error.NotFoundException
import dev.alllexey.itmowidgets.backend.platform.security.ItmoJwtVerifier
import dev.alllexey.itmowidgets.backend.platform.security.ItmoJwtVerifier.Companion.getClaimOrNull
import dev.alllexey.itmowidgets.backend.platform.security.ItmoJwtVerifier.Companion.getIsu
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.*

@Service
class UserService(
    private val userRepository: UserRepository,
    private val itmoJwtVerifier: ItmoJwtVerifier,
    private val userRegistrationService: UserRegistrationService,
    private val userGroupUpdater: UserGroupUpdater,
) {

    fun findOrCreateByIsu(isu: Int): User = userRegistrationService.findOrCreateByIsu(isu)

    fun resolveIdByIsu(isu: Int): UUID = userRegistrationService.resolveIdByIsu(isu)

    /** The user of [isu] if it turned the services on; never creates one. */
    fun enrolledIdByIsu(isu: Int): UUID? = userRepository.findEnrolledIdByIsu(isu)

    /** `PUT /api/users/me/consent` with [consentVersion], or a device registration (null keeps the stored version). */
    fun enroll(userId: UUID, consentVersion: Int?) {
        userRepository.enroll(userId, consentVersion)
    }

    fun privacySettings(user: User): UserPrivacySettings = UserPrivacySettings(
        scheduleVisibility = user.settings.scheduleVisibility,
        sportVisibility = user.settings.sportVisibility,
        friendsVisibility = user.settings.friendsVisibility,
    )

    @Transactional
    fun updatePrivacySettings(user: User, privacy: UserPrivacySettings): UserPrivacySettings {
        // Resolve inside this transaction: a caller may pass an entity detached from its read context.
        val managedUser = findUserById(user.id)
        managedUser.settings.apply {
            scheduleVisibility = privacy.scheduleVisibility
            sportVisibility = privacy.sportVisibility
            friendsVisibility = privacy.friendsVisibility
        }
        return privacySettings(managedUser)
    }

    /** Returns the ID token's ITMO.ID session id (`sid`), when it carries one. */
    @Transactional
    fun updateDataFromIdToken(user: User, idToken: String): String? {
        val token = itmoJwtVerifier.verifyAndDecode(idToken)
        val isu = token.getIsu() ?: throw BusinessRuleException("Not found isu in idToken")
        if (user.isu != isu) throw BusinessRuleException("User isu didn't match isu in idToken")
        // The caller's entity is detached (no open-in-view): change the managed one.
        val managedUser = findUserById(user.id)
        managedUser.pictureUrl = token.getClaimOrNull("picture")?.asString()
        managedUser.familyName = token.getClaimOrNull("family_name")?.asString()?.trim()?.ifEmpty { null }
        managedUser.givenName = token.getClaimOrNull("given_name")?.asString()?.trim()?.ifEmpty { null }
        // ITMO.ID's `name` misses a part and has no fixed order; clients show and abbreviate «Фамилия Имя».
        managedUser.name = personName(managedUser.familyName, managedUser.givenName)
            ?: token.getClaimOrNull("name")?.asString()
        managedUser.email = token.getClaimOrNull("email")?.asString()
        managedUser.gender = token.getClaimOrNull("gender")?.asString()
        userGroupUpdater.updateGroups(managedUser, token)
        return token.getClaimOrNull("sid")?.asString()
    }

    fun findUserByIsu(isu: Int): User = userRepository.findByIsu(isu)
        ?: throw NotFoundException("User not found with isu: $isu")

    /** The ISU of a user without loading the entity (account deletion must not hold it). */
    fun isuOf(id: UUID): Int = userRepository.findIsuById(id) ?: throw NotFoundException("User not found with ID: $id")

    fun findUserById(id: UUID): User = userRepository.findById(id)
        .orElseThrow { NotFoundException("User not found with ID: $id") }
}

private fun personName(familyName: String?, givenName: String?): String? =
    if (familyName != null && givenName != null) "$familyName $givenName" else null
