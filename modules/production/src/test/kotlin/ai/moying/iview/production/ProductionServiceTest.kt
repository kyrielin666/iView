package ai.moying.iview.production
import ai.moying.iview.core.device.*
import java.time.Instant
import kotlin.test.*
class ProductionServiceTest {
    @Test fun `only rising counter creates one delta record`() {
        val repo = MemoryProduction()
        val service = ProductionService(repo)
        service.createRule(ProductionRuleCommand(1, 2))
        val start = Instant.EPOCH
        fun point(value: Long, time: Instant) = PointValue(
            DeviceId(1), PointId(2), time, time, value, ValueQuality.GOOD, "modbus_tcp",
        )
        service.inspect(listOf(point(10, start)))
        service.inspect(listOf(point(10, start.plusSeconds(1))))
        service.inspect(listOf(point(13, start.plusSeconds(2))))
        service.inspect(listOf(point(1, start.plusSeconds(3))))
        assertEquals(1, repo.records.size)
    assertEquals(3, repo.records.single().quantity)
    }

    @Test fun `uses the qualified counter when a rule configures one`() {
        val repo = MemoryProduction(); val service = ProductionService(repo)
        service.createRule(ProductionRuleCommand(1, 2, 3))
        fun point(pointId: Long, value: Long, time: Instant) = PointValue(DeviceId(1), PointId(pointId), time, time, value, ValueQuality.GOOD, "modbus_tcp")
        service.inspect(listOf(point(2, 10, Instant.EPOCH), point(3, 8, Instant.EPOCH)))
        service.inspect(listOf(point(2, 15, Instant.EPOCH.plusSeconds(1)), point(3, 11, Instant.EPOCH.plusSeconds(1))))
        assertEquals(5, repo.records.single().quantity)
        assertEquals(3, repo.records.single().qualifiedQuantity)
    }
}
private class MemoryProduction:ProductionRepository { var id=0L; val rules=mutableListOf<ProductionRule>(); val cps=mutableMapOf<Pair<Long,Long>,ProductionCheckpoint>(); val records=mutableListOf<ProductionRecord>(); override fun listRules()=rules; override fun findRule(id:Long)=rules.find{it.id==id}; override fun createRule(c:ProductionRuleCommand)=ProductionRule(++id,c.templateId,c.quantityPointId,c.qualifiedPointId,c.enabled,1,Instant.EPOCH,Instant.EPOCH).also(rules::add); override fun updateRule(id:Long,c:ProductionRuleCommand)=null; override fun deleteRule(id:Long)=false; override fun checkpoint(d:Long,p:Long)=cps[d to p]; override fun saveCheckpoint(c:ProductionCheckpoint){cps[c.deviceId to c.pointId]=c}; override fun findRecord(d:Long,s:Instant,e:Instant)=records.find{it.deviceId==d&&it.startTime==s&&it.endTime==e}; override fun createRecord(d:Long,q:Long,g:Long,s:Instant,e:Instant,v:String,a:Map<String,String>)=ProductionRecord(++id,d,q,g,s,e,v,a,Instant.EPOCH).also(records::add); override fun recordsInWindow(d:Long,s:Instant,e:Instant)=records.filter{it.deviceId==d&&it.endTime>s&&it.startTime<e}; override fun listRecords(f:ProductionFilter)=ProductionPage(records,records.size.toLong(),f.page,f.pageSize); override fun deleteBefore(c:Instant)=0 }
