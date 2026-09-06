package ai.moying.iview.oee

import ai.moying.iview.production.ProductionService
import ai.moying.iview.state.MachineStateRepository
import ai.moying.iview.plan.ProductionPlanService
import java.time.Duration
import java.time.Instant

data class DeviceOeeResult(val deviceId: Long, val windowStart: Instant, val windowEnd: Instant, val ruleVersions: Set<String>, val result: OeeResult)

class DeviceOeeService(private val states: MachineStateRepository, private val production: ProductionService, private val plans: ProductionPlanService, private val calculator: OeeCalculator = OeeCalculator()) {
    fun calculate(deviceId: Long, start: Instant, end: Instant, planned: Duration?, ideal: Duration?): DeviceOeeResult {
        val window = TimeWindow(start, end)
        val plannedWindows = plans.plannedWindows(deviceId, start, end).map { TimeWindow(it.start, it.end) }
        val plannedDuration = planned ?: plannedWindows.fold(Duration.ZERO) { total, period -> total.plus(period.duration) }
        require(!plannedDuration.isNegative && plannedDuration <= window.duration) { "计划生产时长必须在查询时间窗内" }
        val stored = states.listIntervals(deviceId, start, end)
        val versions = stored.map { it.ruleVersion }.toSortedSet()
        val intervals = stored.map { MachineStateInterval(TimeWindow(it.startedAt, it.endedAt ?: end), it.state, it.ruleVersion) }
        val records = production.recordsInWindow(deviceId, start, end)
        val total = records.sumOf { it.quantity }
        val good = records.sumOf { it.qualifiedQuantity }.coerceAtMost(total)
        val version = "oee/${versions.joinToString(",").ifBlank { "no-state-rule" }}"
        return DeviceOeeResult(deviceId, start, end, versions, calculator.calculate(OeeInput(window, plannedDuration, intervals, ProductionCount(total, good), ideal, version, plannedWindows.ifEmpty { listOf(window) })))
    }
}
