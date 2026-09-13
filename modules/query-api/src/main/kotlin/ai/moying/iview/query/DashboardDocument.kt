package ai.moying.iview.query

enum class DashboardLayoutMode { GRID, FREE }
data class DashboardCanvas(val width: Int = 1920, val height: Int = 1080, val background: String = "#0b1020")
data class DashboardVariable(val name: String, val defaultValue: String? = null, val required: Boolean = false)
data class DashboardConditionalStyle(
    val field: String, val operator: String, val value: String? = null, val style: Map<String, String> = emptyMap(),
)
data class DashboardInteraction(
    val event: String, val action: String, val targetDashboardId: Long? = null, val parameters: Map<String, String> = emptyMap(),
)
data class DashboardRepeatConfig(val maxItems: Int = 10, val direction: String = "vertical", val childIds: List<String> = emptyList())
data class DashboardComponent(
    val id: String, val type: String, val x: Int, val y: Int, val width: Int, val height: Int,
    val modelId: Long? = null, val fields: List<String> = emptyList(), val props: Map<String, Any?> = emptyMap(),
    val conditionalStyles: List<DashboardConditionalStyle> = emptyList(), val interactions: List<DashboardInteraction> = emptyList(), val repeat: DashboardRepeatConfig? = null,
)
data class DashboardDocument(
    val version: Int = 1, val layoutMode: DashboardLayoutMode = DashboardLayoutMode.GRID, val canvas: DashboardCanvas = DashboardCanvas(),
    /** 0 disables managed data refresh; otherwise the browser refreshes bound models at this interval. */
    val refreshIntervalMs: Int = 5_000,
    val variables: List<DashboardVariable> = emptyList(), val components: List<DashboardComponent> = emptyList(),
)

class DashboardDocumentValidator(private val modelById: (Long) -> DataModel) {
    fun validate(document: DashboardDocument, defaultModelId: Long? = null): DashboardDocument {
        if (document.version != 1) throw DashboardValidationException("暂不支持的看板文档版本: ${document.version}")
        if (document.canvas.width !in 320..7680 || document.canvas.height !in 240..4320) throw DashboardValidationException("画布尺寸超出支持范围")
        if (document.refreshIntervalMs !in 0..3_600_000 || (document.refreshIntervalMs != 0 && document.refreshIntervalMs < 1_000)) throw DashboardValidationException("看板刷新间隔必须为 0（关闭）或 1000 到 3600000 毫秒")
        if (document.components.size > 200) throw DashboardValidationException("单个看板最多200个组件")
        if (document.variables.size > 50) throw DashboardValidationException("单个看板最多50个动态变量")
        if (document.variables.map { it.name }.toSet().size != document.variables.size || document.variables.any { !variableName.matches(it.name) }) throw DashboardValidationException("变量名必须以字母或下划线开头，仅包含字母、数字和下划线且不能重复")
        if (document.variables.any { (it.defaultValue?.length ?: 0) > 500 }) throw DashboardValidationException("变量默认值不能超过500个字符")
        if (document.components.map { it.id }.toSet().size != document.components.size || document.components.any { it.id.isBlank() }) throw DashboardValidationException("组件 ID 不能为空且不能重复")
        val componentIds = document.components.map { it.id }.toSet()
        val repeatedChildIds = document.components.flatMap { it.repeat?.childIds ?: emptyList() }
        if (repeatedChildIds.size != repeatedChildIds.toSet().size) throw DashboardValidationException("同一子组件不能被多个重复容器引用")
        val supported = setOf("chart", "table", "metric", "text", "image", "decoration", "container", "tuple", "iframe")
        document.components.forEach { component ->
            if (component.type !in supported) throw DashboardValidationException("不支持的组件类型: ${component.type}")
            if (component.x < 0 || component.y < 0 || component.width !in 1..7680 || component.height !in 1..4320) throw DashboardValidationException("组件布局参数无效: ${component.id}")
            val modelId = component.modelId ?: defaultModelId
            if (component.fields.isNotEmpty() && modelId == null) throw DashboardValidationException("组件 ${component.id} 绑定字段时必须指定数据模型")
            modelId?.let { id -> if (modelById(id).status != DataModelStatus.PUBLISHED) throw DashboardValidationException("组件 ${component.id} 只能绑定已发布数据模型: $id") }
            if (component.repeat != null && component.type != "container") throw DashboardValidationException("仅容器组件支持批量重复")
            component.repeat?.let { repeat ->
                if (repeat.maxItems !in 1..50 || repeat.direction !in setOf("vertical", "horizontal") || repeat.childIds.isEmpty() || repeat.childIds.size > 20) throw DashboardValidationException("组件 ${component.id} 的重复容器配置无效")
                if (component.id in repeat.childIds || repeat.childIds.any { it !in componentIds }) throw DashboardValidationException("组件 ${component.id} 的重复子组件引用无效")
            }
            validateConditionalStyles(component)
            validateInteractions(component)
        }
        validateRepeatGraph(document.components)
        return document
    }

    private fun validateRepeatGraph(components: List<DashboardComponent>) {
        val byId = components.associateBy { it.id }
        val visiting = linkedSetOf<String>()
        fun visit(id: String, depth: Int) {
            if (depth > 5) throw DashboardValidationException("重复容器最多嵌套5层: $id")
            if (id in visiting) throw DashboardValidationException("重复容器不能形成循环引用: ${(visiting + id).joinToString(" -> ")}")
            visiting += id
            byId[id]?.repeat?.childIds.orEmpty().forEach { childId ->
                if (byId[childId]?.type == "container") visit(childId, depth + 1)
            }
            visiting -= id
        }
        components.filter { it.type == "container" && it.repeat != null }.forEach { visit(it.id, 1) }
    }

    private fun validateConditionalStyles(component: DashboardComponent) {
        if (component.conditionalStyles.size > 20) throw DashboardValidationException("组件 ${component.id} 最多20条条件样式")
        component.conditionalStyles.forEach { rule ->
            if (rule.field.isBlank() || rule.field.length > 100) throw DashboardValidationException("组件 ${component.id} 的条件样式字段无效")
            if (rule.operator !in conditionOperators) throw DashboardValidationException("组件 ${component.id} 的条件样式操作符不支持: ${rule.operator}")
            if (rule.operator !in nullOperators && rule.value.isNullOrBlank()) throw DashboardValidationException("组件 ${component.id} 的条件样式缺少比较值")
            if ((rule.value?.length ?: 0) > 500 || rule.style.isEmpty() || rule.style.size > 12 || rule.style.any { (key, value) -> !styleKey.matches(key) || value.length > 200 }) throw DashboardValidationException("组件 ${component.id} 的条件样式配置无效")
        }
    }

    private fun validateInteractions(component: DashboardComponent) {
        if (component.interactions.size > 20) throw DashboardValidationException("组件 ${component.id} 最多20个交互")
        component.interactions.forEach { interaction ->
            if (interaction.event !in interactionEvents || interaction.action !in interactionActions) throw DashboardValidationException("组件 ${component.id} 的交互事件或动作不支持")
            if (interaction.action == "navigate" && (interaction.targetDashboardId == null || interaction.targetDashboardId <= 0)) throw DashboardValidationException("组件 ${component.id} 的跳转交互必须指定目标看板")
            if (interaction.action != "navigate" && interaction.targetDashboardId != null) throw DashboardValidationException("组件 ${component.id} 的非跳转交互不能指定目标看板")
            if (interaction.parameters.size > 20 || interaction.parameters.any { (key, value) -> !variableName.matches(key) || value.length > 500 }) throw DashboardValidationException("组件 ${component.id} 的交互参数无效")
        }
    }

    private companion object {
        val variableName = Regex("[A-Za-z_][A-Za-z0-9_]{0,63}")
        val styleKey = Regex("[A-Za-z][A-Za-z0-9-]{0,63}")
        val conditionOperators = setOf("eq", "ne", "gt", "gte", "lt", "lte", "contains", "is_null", "not_null")
        val nullOperators = setOf("is_null", "not_null")
        val interactionEvents = setOf("click", "change")
        val interactionActions = setOf("filter", "navigate", "set_variable")
    }
}
