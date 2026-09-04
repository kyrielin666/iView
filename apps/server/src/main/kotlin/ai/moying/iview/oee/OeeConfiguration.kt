package ai.moying.iview.oee

import ai.moying.iview.production.ProductionService
import ai.moying.iview.state.MachineStateRepository
import ai.moying.iview.plan.ProductionPlanService
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class OeeConfiguration { @Bean fun deviceOeeService(states: MachineStateRepository, production: ProductionService, plans: ProductionPlanService) = DeviceOeeService(states, production, plans) }
