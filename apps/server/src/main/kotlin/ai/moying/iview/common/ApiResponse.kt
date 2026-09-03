package ai.moying.iview.common

import ai.moying.iview.device.CatalogConflictException
import ai.moying.iview.device.CatalogNotFoundException
import ai.moying.iview.device.CatalogValidationException
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

    private fun response(status: HttpStatus, message: String?) = ResponseEntity
        .status(status)
        .body(ApiResponse<Nothing>(status.value().toString(), message ?: status.reasonPhrase))
}
