package ai.moying.iview.plan
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
@Configuration class ProductionPlanConfiguration{@Bean fun productionPlanService(repository:ProductionPlanRepository)=ProductionPlanService(repository)}
