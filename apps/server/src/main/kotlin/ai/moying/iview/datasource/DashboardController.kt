package ai.moying.iview.datasource

import ai.moying.iview.common.ApiResponse
import ai.moying.iview.query.*
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.*
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter

class DashboardRequest { var folderId:Long?=null;var modelId:Long?=null;var name:String="";var description:String?=null;var contentJson:String?=null }
class DashboardFolderRequest { var name:String="";var parentId:Long?=null }
class DashboardImportRequest { var export: DashboardExport? = null; var folderId: Long? = null; var name: String? = null }
class DashboardCopyRequest { var name: String = "" }
class DashboardMoveRequest { var folderId: Long? = null }
data class DashboardExport(val format: String = "iview-dashboard", val version: Int = 1, val name: String, val description: String, val modelId: Long?, val document: DashboardDocument)
@RestController @RequestMapping("/api/v1/dashboards")
class DashboardController(private val dashboards:DashboardService, private val documents:DashboardDocumentValidator, private val objectMapper:ObjectMapper, private val realtime:DashboardRealtimeHub){
 @GetMapping fun list(@RequestParam(name="folder_id",required=false) folderId:Long?)=ApiResponse.success(dashboards.list(folderId).map(::view))
 @GetMapping("/{id}") fun get(@PathVariable id:Long)=ApiResponse.success(view(dashboards.get(id)))
 @PostMapping @ResponseStatus(HttpStatus.CREATED) fun create(@RequestBody r:DashboardRequest)=ApiResponse.success(view(dashboards.create(r.draft())))
 @PutMapping("/{id}") fun update(@PathVariable id:Long,@RequestBody r:DashboardRequest)=ApiResponse.success(view(dashboards.update(id,r.draft())))
 @DeleteMapping("/{id}") fun delete(@PathVariable id:Long):ApiResponse<Nothing>{dashboards.delete(id);return ApiResponse.success()}
 @PostMapping("/{id}/copy") fun copy(@PathVariable id: Long, @RequestBody request: DashboardCopyRequest) = ApiResponse.success(view(dashboards.copy(id, request.name)))
 @PutMapping("/{id}/folder") fun move(@PathVariable id: Long, @RequestBody request: DashboardMoveRequest) = ApiResponse.success(view(dashboards.move(id, request.folderId)))
 @PutMapping("/{id}/document") fun document(@PathVariable id: Long, @RequestBody document: DashboardDocument): ApiResponse<DashboardDocument> {
  val dashboard = dashboards.get(id); val validated = documents.validate(document, dashboard.modelId)
  dashboards.replaceContent(id, objectMapper.writeValueAsString(validated)); realtime.notifyChanged(id, "document-updated"); return ApiResponse.success(validated)
 }
 @GetMapping("/{id}/preview") fun preview(@PathVariable id: Long) = ApiResponse.success(readDocument(dashboards.get(id).contentJson))
 @PostMapping("/{id}/publish") fun publish(@PathVariable id:Long): ApiResponse<Map<String, Any?>> {
  val dashboard = dashboards.get(id); documents.validate(readDocument(dashboard.contentJson), dashboard.modelId)
  val published = dashboards.publish(id); realtime.notifyChanged(id, "published"); return ApiResponse.success(view(published))
 }
 @GetMapping("/{id}/subscription", produces = [MediaType.TEXT_EVENT_STREAM_VALUE]) fun subscription(@PathVariable id: Long): SseEmitter { dashboards.get(id); return realtime.subscribe(id) }
 @GetMapping("/{id}/published") fun published(@PathVariable id: Long) = ApiResponse.success(mapOf("snapshot_id" to dashboards.published(id).id, "document" to readDocument(dashboards.published(id).contentJson)))
 @GetMapping("/{id}/snapshots") fun snapshots(@PathVariable id:Long)=ApiResponse.success(dashboards.snapshots(id).map { mapOf("id" to it.id, "version" to it.version, "content_json" to it.contentJson, "model_id" to it.modelId, "created_at" to it.createdAt) })
 @GetMapping("/{id}/export") fun export(@PathVariable id: Long): ApiResponse<DashboardExport> {
  val dashboard = dashboards.get(id); val document = documents.validate(readDocument(dashboard.contentJson), dashboard.modelId)
  return ApiResponse.success(DashboardExport(name = dashboard.name, description = dashboard.description, modelId = dashboard.modelId, document = document))
 }
 @PostMapping("/import") @ResponseStatus(HttpStatus.CREATED) fun import(@RequestBody request: DashboardImportRequest): ApiResponse<Map<String, Any?>> {
  val exported = request.export ?: throw DashboardValidationException("缺少待导入的看板内容")
  if (exported.format != "iview-dashboard" || exported.version != 1) throw DashboardValidationException("不支持的看板导入格式或版本")
  val modelId = exported.modelId
  val document = documents.validate(exported.document, modelId)
  val imported = dashboards.create(DashboardDraft(request.folderId, modelId, request.name?.takeIf { it.isNotBlank() } ?: exported.name, exported.description, objectMapper.writeValueAsString(document)))
  return ApiResponse.success(view(imported))
 }
 private fun DashboardRequest.draft()=DashboardDraft(folderId,modelId,name,description?:"",contentJson?:"{}")
 private fun readDocument(content: String): DashboardDocument = try { objectMapper.readValue(content, DashboardDocument::class.java) } catch (_: Exception) { throw DashboardValidationException("看板文档 JSON 无效，请通过 document 接口保存") }
 private fun view(d:Dashboard)=mapOf("id" to d.id, "folder_id" to d.folderId, "model_id" to d.modelId, "name" to d.name, "description" to d.description, "content_json" to d.contentJson, "status" to d.status.name.lowercase(), "published_snapshot_id" to d.publishedSnapshotId, "created_at" to d.createdAt, "updated_at" to d.updatedAt)
}
@RestController @RequestMapping("/api/v1/dashboard-folders")
class DashboardFolderController(private val dashboards:DashboardService){
 @GetMapping fun list()=ApiResponse.success(dashboards.listFolders().map(::view))
 @PostMapping @ResponseStatus(HttpStatus.CREATED) fun create(@RequestBody r:DashboardFolderRequest)=ApiResponse.success(view(dashboards.createFolder(DashboardFolderDraft(r.name,r.parentId))))
 @PutMapping("/{id}") fun update(@PathVariable id:Long,@RequestBody r:DashboardFolderRequest)=ApiResponse.success(view(dashboards.updateFolder(id,DashboardFolderDraft(r.name,r.parentId))))
 @DeleteMapping("/{id}") fun delete(@PathVariable id:Long):ApiResponse<Nothing>{dashboards.deleteFolder(id);return ApiResponse.success()}
 private fun view(f:DashboardFolder)=mapOf("id" to f.id, "name" to f.name, "parent_id" to f.parentId, "created_at" to f.createdAt, "updated_at" to f.updatedAt)
}
