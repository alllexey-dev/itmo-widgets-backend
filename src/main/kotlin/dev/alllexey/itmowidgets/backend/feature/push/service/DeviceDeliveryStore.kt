package dev.alllexey.itmowidgets.backend.feature.push.service

import dev.alllexey.itmowidgets.backend.feature.push.persistence.DeviceRepository
import dev.alllexey.itmowidgets.backend.feature.push.web.DeviceDeliveryTarget
import java.util.UUID
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

@Service
class DeviceDeliveryStore(private val deviceRepository: DeviceRepository) {
    @Transactional(readOnly = true)
    fun targetsFor(userId: UUID): List<DeviceDeliveryTarget> =
        deviceRepository.findByUserId(userId).map { DeviceDeliveryTarget(it.id, it.fcmToken, it.user.isu) }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun removeIfTokenMatches(target: DeviceDeliveryTarget): Int =
        deviceRepository.deleteIfTokenMatches(target.deviceId, target.fcmToken)
}
