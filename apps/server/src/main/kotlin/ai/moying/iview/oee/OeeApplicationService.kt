package ai.moying.iview.oee

import ai.moying.iview.production.ProductionService
import ai.moying.iview.state.MachineStateRepository
import ai.moying.iview.plan.ProductionPlanService
import ai.moying.iview.state.MachineStateCorrection
import ai.moying.iview.state.StoredMachineStateInterval
import java.time.Duration
import java.time.Instant

data class OeeProductionTrace(val recordId: Long, val quantity: Long, val qualifiedQuantity: Long, val start: Instant, val end: Instant, val ruleVersion: String)
data class OeeCalculationTrace(
    val plannedWindows: List<TimeWindow>,
    val rawStateIntervals: List<StoredMachineStateInterval>,
    val corrections: List<MachineStateCorrection>,
    val effectiveStateIntervals: List<MachineStateInterval>,
    val productionRecords: List<OeeProductionTrace>,
)
data class DeviceOeeResult(val deviceId: Long, val windowStart: Instant, val windowEnd: Instant, val ruleVersions: Set<String>, val result: OeeResult, val trace: OeeCalculationTrace)

class DeviceOeeService(private val states: MachineStateRepository, private val production: ProductionService, private val plans: ProductionPlanService, private val calculator: OeeCalculator = OeeCalculator()) {
    fun calculate(deviceId: Long, start: Instant, end: Instant, planned: Duration?, ideal: Duration?): DeviceOeeResult {
        val window = TimeWindow(start, end)
        val plannedWindows = plans.plannedWindows(deviceId, start, end).map { TimeWindow(it.start, it.end) }
        val plannedDuration = planned ?: if (plannedWindows.isEmpty()) window.duration else plannedWindows.fold(Duration.ZERO) { total, period -> total.plus(period.duration) }
        require(!plannedDuration.isNegative && plannedDuration <= window.duration) { "计划生产时长必须在查询时间窗内" }
        val stored = states.listIntervals(deviceId, start, end)
        val corrections = states.listCorrections(deviceId, start, end)
        val intervals = effectiveIntervals(stored, corrections, start, end)
        val versions = intervals.map { it.ruleVersion }.toSortedSet()
        val records = production.recordsInWindow(deviceId, start, end)
        val total = records.sumOf { it.quantity }
        val good = records.sumOf { it.qualifiedQuantity }.coerceAtMost(total)
        val productionVersions = records.map { it.ruleVersion }.toSortedSet()
        val version = "oee/state=${versions.joinToString(",").ifBlank { "none" }};production=${productionVersions.joinToString(",").ifBlank { "none" }}"
        val activeWindows = plannedWindows.ifEmpty { listOf(window) }
        val result = calculator.calculate(OeeInput(window, plannedDuration, intervals, ProductionCount(total, good), ideal, version, activeWindows))
        val trace = OeeCalculationTrace(activeWindows, stored, corrections, intervals, records.map { OeeProductionTrace(it.id, it.quantity, it.qualifiedQuantity, it.startTime, it.endTime, it.ruleVersion) })
        return DeviceOeeResult(deviceId, start, end, versions, result, trace)
    }

    private fun effectiveIntervals(raw: List<StoredMachineStateInterval>, corrections: List<MachineStateCorrection>, start: Instant, end: Instant): List<MachineStateInterval> {
        val boundaries = sortedSetOf(start, end)
        raw.forEach { interval -> boundaries += maxOf(start, interval.startedAt); boundaries += minOf(end, interval.endedAt ?: end) }
        corrections.forEach { correction -> boundaries += maxOf(start, correction.startedAt); boundaries += minOf(end, correction.endedAt) }
        val segments = boundaries.zipWithNext().mapNotNull { (segmentStart, segmentEnd) ->
            if (!segmentEnd.isAfter(segmentStart)) return@mapNotNull null
            val correction = corrections.filter { it.startedAt < segmentEnd && it.endedAt > segmentStart }.maxByOrNull { it.id }
            if (correction != null) return@mapNotNull MachineStateInterval(TimeWindow(segmentStart, segmentEnd), correction.state, "manual-correction-${correction.id}")
            val source = raw.filter { it.startedAt < segmentEnd && (it.endedAt ?: end) > segmentStart }.maxByOrNull { it.startedAt } ?: return@mapNotNull null
            MachineStateInterval(TimeWindow(segmentStart, segmentEnd), source.state, source.ruleVersion)
        }
        return segments.fold(mutableListOf()) { merged, current ->
            val previous = merged.lastOrNull()
            if (previous != null && previous.state == current.state && previous.ruleVersion == current.ruleVersion && previous.window.endExclusive == current.window.start) {
                merged[merged.lastIndex] = previous.copy(window = TimeWindow(previous.window.start, current.window.endExclusive))
            } else merged += current
            merged
        }
    }
}
