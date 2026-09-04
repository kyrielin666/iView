package ai.moying.iview.common

import ai.moying.iview.device.CatalogConflictException
import ai.moying.iview.device.CatalogNotFoundException
import ai.moying.iview.device.CatalogValidationException
import ai.moying.iview.device.ControlExecutionException
import ai.moying.iview.outbound.PushNotFoundException
import ai.moying.iview.outbound.PushValidationException
import ai.moying.iview.alarm.AlarmNotFoundException
import ai.moying.iview.alarm.AlarmValidationException
import ai.moying.iview.state.MachineStateNotFoundException
import ai.moying.iview.state.MachineStateValidationException
import ai.moying.iview.production.ProductionNotFoundException
import ai.moying.iview.production.ProductionValidationException
import ai.moying.iview.plan.ProductionPlanNotFoundException
import ai.moying.iview.plan.ProductionPlanValidationException
import ai.moying.iview.query.QueryValidationException
import ai.moying.iview.query.DataSourceNotFoundException
import ai.moying.iview.query.DataSourceValidationException
import ai.moying.iview.query.DatasetNotFoundException
import ai.moying.iview.query.DatasetValidationException
import ai.moying.iview.query.DataModelNotFoundException
import ai.moying.iview.query.DataModelValidationException
import ai.moying.iview.query.DashboardNotFoundException
import ai.moying.iview.query.DashboardValidationException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

data class ApiResponse<T>(
    val code: String,
    val msg: String,
    val data: T? = null,
) {
    companion object {
        fun <T> success(data: T? = null) = ApiResponse("200", "success", data)
    }
}

@RestControllerAdvice
class ApiExceptionHandler {
    @ExceptionHandler(CatalogNotFoundException::class)
    fun notFound(error: CatalogNotFoundException) = response(HttpStatus.NOT_FOUND, error.message)

    @ExceptionHandler(CatalogConflictException::class)
    fun conflict(error: CatalogConflictException) = response(HttpStatus.CONFLICT, error.message)

    @ExceptionHandler(CatalogValidationException::class, IllegalArgumentException::class)
    fun validation(error: RuntimeException) = response(HttpStatus.BAD_REQUEST, error.message)

    @ExceptionHandler(ControlExecutionException::class)
    fun controlFailure(error: ControlExecutionException) = response(HttpStatus.INTERNAL_SERVER_ERROR, error.message)

    @ExceptionHandler(PushNotFoundException::class)
    fun pushNotFound(error: PushNotFoundException) = response(HttpStatus.NOT_FOUND, error.message)

    @ExceptionHandler(PushValidationException::class)
    fun pushValidation(error: PushValidationException) = response(HttpStatus.BAD_REQUEST, error.message)

    @ExceptionHandler(AlarmNotFoundException::class)
    fun alarmNotFound(error: AlarmNotFoundException) = response(HttpStatus.NOT_FOUND, error.message)

    @ExceptionHandler(AlarmValidationException::class)
    fun alarmValidation(error: AlarmValidationException) = response(HttpStatus.BAD_REQUEST, error.message)

    @ExceptionHandler(MachineStateNotFoundException::class)
    fun stateNotFound(error: MachineStateNotFoundException) = response(HttpStatus.NOT_FOUND, error.message)

    @ExceptionHandler(MachineStateValidationException::class)
    fun stateValidation(error: MachineStateValidationException) = response(HttpStatus.BAD_REQUEST, error.message)

    @ExceptionHandler(ProductionNotFoundException::class)
    fun productionNotFound(error: ProductionNotFoundException) = response(HttpStatus.NOT_FOUND, error.message)

    @ExceptionHandler(ProductionValidationException::class)
    fun productionValidation(error: ProductionValidationException) = response(HttpStatus.BAD_REQUEST, error.message)

    @ExceptionHandler(ProductionPlanNotFoundException::class)
    fun planNotFound(error: ProductionPlanNotFoundException) = response(HttpStatus.NOT_FOUND, error.message)

    @ExceptionHandler(ProductionPlanValidationException::class)
    fun planValidation(error: ProductionPlanValidationException) = response(HttpStatus.BAD_REQUEST, error.message)

    @ExceptionHandler(QueryValidationException::class)
    fun queryValidation(error: QueryValidationException) = response(HttpStatus.BAD_REQUEST, error.message)

    @ExceptionHandler(DataSourceNotFoundException::class)
    fun dataSourceNotFound(error: DataSourceNotFoundException) = response(HttpStatus.NOT_FOUND, error.message)

    @ExceptionHandler(DataSourceValidationException::class)
    fun dataSourceValidation(error: DataSourceValidationException) = response(HttpStatus.BAD_REQUEST, error.message)

    @ExceptionHandler(DatasetNotFoundException::class)
    fun datasetNotFound(error: DatasetNotFoundException) = response(HttpStatus.NOT_FOUND, error.message)

    @ExceptionHandler(DatasetValidationException::class)
    fun datasetValidation(error: DatasetValidationException) = response(HttpStatus.BAD_REQUEST, error.message)

    @ExceptionHandler(DataModelNotFoundException::class)
    fun dataModelNotFound(error: DataModelNotFoundException) = response(HttpStatus.NOT_FOUND, error.message)

    @ExceptionHandler(DataModelValidationException::class)
    fun dataModelValidation(error: DataModelValidationException) = response(HttpStatus.BAD_REQUEST, error.message)

    @ExceptionHandler(DashboardNotFoundException::class)
    fun dashboardNotFound(error: DashboardNotFoundException) = response(HttpStatus.NOT_FOUND, error.message)

    @ExceptionHandler(DashboardValidationException::class)
    fun dashboardValidation(error: DashboardValidationException) = response(HttpStatus.BAD_REQUEST, error.message)

    private fun response(status: HttpStatus, message: String?) = ResponseEntity
        .status(status)
        .body(ApiResponse<Nothing>(status.value().toString(), message ?: status.reasonPhrase))
}
