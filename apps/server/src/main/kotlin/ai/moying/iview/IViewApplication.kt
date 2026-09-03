package ai.moying.iview

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@EnableScheduling
class IViewApplication

fun main(args: Array<String>) {
    runApplication<IViewApplication>(*args)
}
