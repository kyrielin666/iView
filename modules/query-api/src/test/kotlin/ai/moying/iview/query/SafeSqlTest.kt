package ai.moying.iview.query
import kotlin.test.*
class SafeSqlTest {
 @Test fun `accepts select and adds limit`(){assertEquals("SELECT id FROM device LIMIT 100",SafeSql.limit("SELECT id FROM device",100))}
 @Test fun `rejects writes multi statements and comments`(){listOf("DELETE FROM x","SELECT 1; DELETE FROM x","SELECT 1 -- x").forEach{assertFailsWith<QueryValidationException>{SafeSql.selectOnly(it)}}}
 @Test fun `binds dashboard variables as JDBC parameters`(){
  val bound=DatasetSql.bindVariables("SELECT * FROM orders WHERE line={{line}} AND shift={{shift}}",mapOf("line" to "A' OR 1=1", "shift" to "N"))
  assertEquals("SELECT * FROM orders WHERE line=? AND shift=?",bound.sql);assertEquals(listOf("A' OR 1=1","N"),bound.values)
 }
 @Test fun `requires every query variable`(){assertFailsWith<DatasetValidationException>{DatasetSql.bindVariables("SELECT * FROM x WHERE id={{id}}",emptyMap())}}
}
