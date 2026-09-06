package ai.moying.iview.oee

import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class OeeCalculatorTest {
    private val shiftStart = Instant.parse("2026-09-03T00:00:00Z")

    @Test
    fun `calculates availability performance quality and oee`() {
        val result = OeeCalculator().calculate(
            OeeInput(
                calculationWindow = window(0, 8),
                plannedProduction = Duration.ofHours(8),
                stateIntervals = listOf(
                    MachineStateInterval(window(0, 6), MachineState.RUNNING, "state-v1"),
                    MachineStateInterval(window(6, 8), MachineState.UNPLANNED_STOP, "state-v1"),
                ),
                production = ProductionCount(total = 100, good = 90),
                idealCycleTime = Duration.ofSeconds(180),
                calculationVersion = "oee-v1",
            )
        )
        assertEquals(0.75, result.availability)
        assertEquals(5.0 / 6.0, result.performance!!, 0.000001)
        assertEquals(0.9, result.quality)
        assertEquals(0.5625, result.oee!!, 0.000001)
    }

    @Test
    fun `clips state intervals to calculation window`() {
        val result = OeeCalculator().calculate(
            OeeInput(
                calculationWindow = window(1, 3),
                plannedProduction = Duration.ofHours(2),
                stateIntervals = listOf(MachineStateInterval(window(0, 2), MachineState.RUNNING, "state-v1")),
                production = ProductionCount(0, 0),
                idealCycleTime = null,
                calculationVersion = "oee-v1",
            )
        )
        assertEquals(Duration.ofHours(1), result.running)
        assertEquals(0.5, result.availability)
    }

    @Test fun `counts running time only inside planned windows`() {
        val result = OeeCalculator().calculate(OeeInput(window(0, 8), Duration.ofHours(4), listOf(MachineStateInterval(window(0, 8), MachineState.RUNNING, "state-v1")), ProductionCount(0, 0), null, "oee-v1", listOf(window(2, 6))))
        assertEquals(Duration.ofHours(4), result.running)
        assertEquals(1.0, result.availability)
    }

    private fun window(startHour: Long, endHour: Long) = TimeWindow(
        start = shiftStart.plus(Duration.ofHours(startHour)),
        endExclusive = shiftStart.plus(Duration.ofHours(endHour)),
    )
}
