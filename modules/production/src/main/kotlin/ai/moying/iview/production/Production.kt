package ai.moying.iview.production

import ai.moying.iview.core.device.PointValue
import ai.moying.iview.core.device.ValueQuality
import java.time.Instant

data class ProductionRule(val id: Long, val templateId: Long, val quantityPointId: Long, val qualifiedPointId: Long?, val enabled: Boolean, val version: Long, val createdAt: Instant, val updatedAt: Instant)
data class ProductionRuleCommand(val templateId: Long, val quantityPointId: Long, val qualifiedPointId: Long? = null, val enabled: Boolean = true)
data class ProductionRecord(val id: Long, val deviceId: Long, val quantity: Long, val qualifiedQuantity: Long, val startTime: Instant, val endTime: Instant, val ruleVersion: String, val attrs: Map<String, String>, val createdAt: Instant)
data class ProductionCheckpoint(val deviceId: Long, val pointId: Long, val quantity: Long, val observedAt: Instant)
data class ProductionPage<T>(val list: List<T>, val total: Long, val page: Int, val pageSize: Int)
data class ProductionFilter(val page: Int = 1, val pageSize: Int = 20, val deviceId: Long? = null)

interface ProductionRepository {
 fun listRules(): List<ProductionRule>; fun findRule(id: Long): ProductionRule?; fun createRule(c: ProductionRuleCommand): ProductionRule; fun updateRule(id: Long, c: ProductionRuleCommand): ProductionRule?; fun deleteRule(id: Long): Boolean
 fun checkpoint(deviceId: Long, pointId: Long): ProductionCheckpoint?; fun saveCheckpoint(c: ProductionCheckpoint)
 fun findRecord(deviceId: Long, start: Instant, end: Instant): ProductionRecord?; fun createRecord(deviceId: Long, quantity: Long, qualified: Long, start: Instant, end: Instant, ruleVersion: String, attrs: Map<String, String>): ProductionRecord
 fun recordsInWindow(deviceId: Long, start: Instant, endExclusive: Instant): List<ProductionRecord>
 fun listRecords(filter: ProductionFilter): ProductionPage<ProductionRecord>; fun deleteBefore(cutoff: Instant): Int
}
open class ProductionException(message: String) : RuntimeException(message)
class ProductionNotFoundException(message: String) : ProductionException(message)
class ProductionValidationException(message: String) : ProductionException(message)

class ProductionService(private val repository: ProductionRepository) {
 fun listRules() = repository.listRules(); fun getRule(id: Long) = repository.findRule(id) ?: throw ProductionNotFoundException("产量规则不存在: $id")
 fun createRule(c: ProductionRuleCommand): ProductionRule { validate(c); return repository.createRule(c) }
 fun updateRule(id: Long, c: ProductionRuleCommand): ProductionRule { getRule(id); validate(c); return repository.updateRule(id,c) ?: throw ProductionNotFoundException("产量规则不存在: $id") }
 fun deleteRule(id: Long) { if (!repository.deleteRule(id)) throw ProductionNotFoundException("产量规则不存在: $id") }
 fun setEnabled(id: Long, enabled: Boolean): ProductionRule { val r=getRule(id); return updateRule(id, ProductionRuleCommand(r.templateId,r.quantityPointId,r.qualifiedPointId,enabled)) }
 fun listRecords(filter: ProductionFilter)=repository.listRecords(filter.copy(page=filter.page.coerceAtLeast(1),pageSize=filter.pageSize.coerceIn(1,100)))
 fun cleanup(cutoff: Instant)=repository.deleteBefore(cutoff)
 fun recordsInWindow(deviceId: Long, start: Instant, endExclusive: Instant) = repository.recordsInWindow(deviceId, start, endExclusive)
 fun inspect(values: List<PointValue>) {
  val rules=repository.listRules().filter { it.enabled }.associateBy { it.quantityPointId }
  values.filter { it.quality==ValueQuality.GOOD }.forEach { value ->
   val rule=rules[value.pointId.value] ?: return@forEach; val current=value.longValue() ?: return@forEach
   val previous=repository.checkpoint(value.deviceId.value, value.pointId.value)
   if (previous != null && current > previous.quantity) {
    val existing=repository.findRecord(value.deviceId.value, previous.observedAt, maxOf(value.observedAt,previous.observedAt))
    if(existing==null) { val delta=current-previous.quantity; repository.createRecord(value.deviceId.value,delta,delta,previous.observedAt,maxOf(value.observedAt,previous.observedAt),"production-rule-${rule.id}-v${rule.version}",mapOf("source" to value.source,"counter_point_id" to value.pointId.value.toString())) }
   }
   repository.saveCheckpoint(ProductionCheckpoint(value.deviceId.value,value.pointId.value,current,value.observedAt))
  }
 }
 private fun validate(c: ProductionRuleCommand) { if(c.templateId<=0||c.quantityPointId<=0||c.qualifiedPointId?.let { it<=0 }==true) throw ProductionValidationException("模板和产量采集点必须有效") }
 private fun PointValue.longValue() = when(val v=value){ is Number->v.toLong(); is String->v.toLongOrNull(); else->null }
}
