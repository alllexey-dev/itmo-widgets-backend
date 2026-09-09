package dev.alllexey.itmowidgets.backend.services

import dev.alllexey.itmowidgets.backend.dto.DeviceDeliveryTarget
import dev.alllexey.itmowidgets.backend.repositories.DeviceRepository
import java.util.UUID
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

@Service
class DeviceDeliveryStore(private val deviceRepository: DeviceRepository) {
    @Transactional(readOnly = true)
    fun targetsFor(userId: UUID): List<DeviceDeliveryTarget> =
        deviceRepository.findByUserId(userId).map { DeviceDeliveryTarget(it.id, it.fcmToken) }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun removeIfTokenMatches(target: DeviceDeliveryTarget): Int =
        deviceRepository.deleteIfTokenMatches(target.deviceId, target.fcmToken)
}
