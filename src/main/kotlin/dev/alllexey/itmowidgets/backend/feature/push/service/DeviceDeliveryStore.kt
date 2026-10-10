package dev.alllexey.itmowidgets.backend.feature.push.service

import dev.alllexey.itmowidgets.backend.feature.push.persistence.DeviceRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class DeviceDeliveryStore(private val deviceRepository: DeviceRepository) {
    @Transactional(readOnly = true)
    fun targetsFor(userId: UUID): List<DeviceDeliveryTarget> =
        deviceRepository.findDeliverableByUserId(userId).map { DeviceDeliveryTarget(it.id, it.fcmToken, it.user.isu) }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun removeIfTokenMatches(target: DeviceDeliveryTarget): Int = deviceRepository.deleteIfTokenMatches(target.deviceId, target.fcmToken)
}
