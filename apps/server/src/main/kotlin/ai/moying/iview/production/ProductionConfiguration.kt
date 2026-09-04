package ai.moying.iview.production
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
@Configuration class ProductionConfiguration { @Bean fun productionService(repository:ProductionRepository)=ProductionService(repository) }
