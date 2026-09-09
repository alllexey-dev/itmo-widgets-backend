package dev.alllexey.itmowidgets.backend.services

import dev.alllexey.itmowidgets.backend.dto.SportNotificationIntent
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class SportNotificationDeliveryService(
    private val transitions: SportQueueTransitionService,
    private val devices: DeviceService,
) {
    /** Best effort after reservation commit. A send already in progress cannot be recalled. */
    fun deliver(intent: SportNotificationIntent) {
        if (!transitions.isIntentCurrent(intent)) return
        devices.sendDataMessageToUser(intent.userId, intent.payload)
    }
}
