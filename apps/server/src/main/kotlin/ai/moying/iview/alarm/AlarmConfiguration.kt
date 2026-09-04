package ai.moying.iview.alarm

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class AlarmConfiguration {
    @Bean fun alarmService(repository: AlarmRepository) = AlarmService(repository)
    @Bean fun collectionAlarmMonitor(service: AlarmService) = CollectionAlarmMonitor(service)
}
