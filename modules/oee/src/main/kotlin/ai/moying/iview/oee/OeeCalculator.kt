package ai.moying.iview.oee

import java.time.Duration
import java.time.Instant

enum class MachineState { RUNNING, IDLE, PLANNED_STOP, UNPLANNED_STOP, FAULT, OFFLINE, UNKNOWN }

data class TimeWindow(val start: Instant, val endExclusive: Instant) {
    init { require(endExclusive.isAfter(start)) { "A time window must have positive duration" } }
    val duration: Duration = Duration.between(start, endExclusive)
}

data class MachineStateInterval(
    val window: TimeWindow,
    val state: MachineState,
    val ruleVersion: String,
)

data class ProductionCount(val total: Long, val good: Long) {
    init {
        require(total >= 0) { "Total production cannot be negative" }
        require(good in 0..total) { "Good production must be between zero and total production" }
    }
}

data class OeeInput(
    val calculationWindow: TimeWindow,
    val plannedProduction: Duration,
    val stateIntervals: List<MachineStateInterval>,
    val production: ProductionCount,
    val idealCycleTime: Duration?,
    val calculationVersion: String,
    val plannedWindows: List<TimeWindow> = listOf(calculationWindow),
)

data class OeeResult(
    val plannedProduction: Duration,
    val running: Duration,
    val availability: Double,
    val performance: Double?,
    val quality: Double?,
    val oee: Double?,
    val calculationVersion: String,
)

class OeeCalculator {
    fun calculate(input: OeeInput): OeeResult {
        require(!input.plannedProduction.isNegative) { "Planned production cannot be negative" }
        require(input.plannedProduction <= input.calculationWindow.duration) {
            "Planned production cannot exceed the calculation window"
        }
        val plannedWindows = merge(input.plannedWindows.mapNotNull { clip(it, input.calculationWindow) })
        val running = input.stateIntervals.asSequence()
            .filter { it.state == MachineState.RUNNING }
            .flatMap { interval -> plannedWindows.asSequence().mapNotNull { planned -> intersect(interval.window, planned) } }
            .fold(Duration.ZERO, Duration::plus)
            .coerceAtMost(input.plannedProduction)
        val availability = ratio(running, input.plannedProduction) ?: 0.0
        val performance = input.idealCycleTime?.let { cycle ->
            if (running.isZero || input.production.total == 0L) 0.0
            else ((cycle.toNanos().toDouble() * input.production.total) / running.toNanos()).coerceIn(0.0, 1.0)
        }
        val quality = if (input.production.total == 0L) null
        else input.production.good.toDouble() / input.production.total
        return OeeResult(
            plannedProduction = input.plannedProduction,
            running = running,
            availability = availability,
            performance = performance,
            quality = quality,
            oee = if (performance != null && quality != null) availability * performance * quality else null,
            calculationVersion = input.calculationVersion,
        )
    }

    private fun intersect(left: TimeWindow, right: TimeWindow): Duration? {
        val start = maxOf(left.start, right.start)
        val end = minOf(left.endExclusive, right.endExclusive)
        return if (end.isAfter(start)) Duration.between(start, end) else null
    }

    private fun clip(left: TimeWindow, right: TimeWindow): TimeWindow? {
        val start = maxOf(left.start, right.start); val end = minOf(left.endExclusive, right.endExclusive)
        return if (end.isAfter(start)) TimeWindow(start, end) else null
    }

    private fun merge(windows: List<TimeWindow>): List<TimeWindow> {
        val merged = mutableListOf<TimeWindow>()
        windows.sortedBy { it.start }.forEach { window ->
            val previous = merged.lastOrNull()
            if (previous == null || window.start.isAfter(previous.endExclusive)) merged += window
            else merged[merged.lastIndex] = TimeWindow(previous.start, maxOf(previous.endExclusive, window.endExclusive))
        }
        return merged
    }

    private fun ratio(numerator: Duration, denominator: Duration): Double? =
        if (denominator.isZero) null else numerator.toNanos().toDouble() / denominator.toNanos()
}
