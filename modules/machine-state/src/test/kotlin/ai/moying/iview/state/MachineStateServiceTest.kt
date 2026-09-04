package ai.moying.iview.state

import ai.moying.iview.core.device.DeviceId
import ai.moying.iview.core.device.PointId
import ai.moying.iview.core.device.PointValue
import ai.moying.iview.core.device.ValueQuality
import ai.moying.iview.oee.MachineState
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class MachineStateServiceTest {
    @Test fun `normalizes numeric point states and records rule version`() {
        val repository = InMemoryStates(); val service = MachineStateService(repository)
        service.createRule(MachineStateRuleCommand(1, 8, mapOf("1" to "normal", "0" to "fault"), mapOf("1" to MachineState.RUNNING, "0" to MachineState.FAULT)))
        service.inspect(listOf(PointValue(DeviceId(3), PointId(8), Instant.EPOCH, Instant.EPOCH, 1, ValueQuality.GOOD, "modbus_tcp")))
        val current = repository.current(3)!!
        assertEquals("normal", current.standardStatus); assertEquals(MachineState.RUNNING, current.runtimeState)
        assertEquals("state-rule-1-v1", current.ruleVersion)
    }
}

private class InMemoryStates : MachineStateRepository {
    private var id = 0L; private val rules = mutableListOf<MachineStateRule>(); private val current = mutableMapOf<Long, CurrentMachineState>()
    override fun listRules() = rules.toList(); override fun findRule(id: Long) = rules.find { it.id == id }
    override fun createRule(command: MachineStateRuleCommand) = MachineStateRule(++id, command.templateId, command.pointId, command.standardMapping, command.runtimeMapping, command.defaultStatus, command.defaultRuntimeState, command.enabled, 1, Instant.EPOCH, Instant.EPOCH).also(rules::add)
    override fun updateRule(id: Long, command: MachineStateRuleCommand) = findRule(id)?.let { old -> old.copy(standardMapping = command.standardMapping, runtimeMapping = command.runtimeMapping, defaultStatus = command.defaultStatus, defaultRuntimeState = command.defaultRuntimeState, enabled = command.enabled, version = old.version + 1).also { rules[rules.indexOf(old)] = it } }
    override fun deleteRule(id: Long) = rules.removeIf { it.id == id }; override fun current(deviceId: Long) = current[deviceId]
    override fun record(next: CurrentMachineState): Boolean { current[next.deviceId] = next; return true }
    override fun listIntervals(deviceId: Long, start: Instant, endExclusive: Instant) = emptyList<StoredMachineStateInterval>()
}
