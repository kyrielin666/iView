package ai.moying.iview.state

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class MachineStateConfiguration {
    @Bean fun machineStateService(repository: MachineStateRepository) = MachineStateService(repository)
}
