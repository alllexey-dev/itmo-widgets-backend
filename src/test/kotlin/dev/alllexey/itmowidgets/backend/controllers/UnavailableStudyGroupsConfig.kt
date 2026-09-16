package dev.alllexey.itmowidgets.backend.controllers

import dev.alllexey.itmowidgets.backend.services.CurrentStudyGroupsService
import dev.alllexey.itmowidgets.backend.services.OfficialStudyGroupsSource
import dev.alllexey.itmowidgets.backend.services.StudyGroupsUnavailable
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import java.time.Clock

@TestConfiguration(proxyBeanMethods = false)
class UnavailableStudyGroupsConfig {
    @Bean fun currentStudyGroups(clock: Clock) = CurrentStudyGroupsService(
        OfficialStudyGroupsSource { throw StudyGroupsUnavailable(temporary = false) }, clock
    )
}
