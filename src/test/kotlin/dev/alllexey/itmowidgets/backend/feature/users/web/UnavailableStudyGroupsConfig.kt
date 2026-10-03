package dev.alllexey.itmowidgets.backend.feature.users.web

import dev.alllexey.itmowidgets.backend.feature.users.service.CurrentStudyGroupsService
import dev.alllexey.itmowidgets.backend.feature.users.service.OfficialStudyGroupsSource
import dev.alllexey.itmowidgets.backend.feature.users.service.StudyGroupsUnavailable
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import java.time.Clock

@TestConfiguration(proxyBeanMethods = false)
class UnavailableStudyGroupsConfig {
    @Bean fun currentStudyGroups(clock: Clock) = CurrentStudyGroupsService(
        OfficialStudyGroupsSource { throw StudyGroupsUnavailable(temporary = false) },
        clock,
    )
}
