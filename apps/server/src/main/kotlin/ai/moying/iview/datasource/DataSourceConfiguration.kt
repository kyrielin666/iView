package ai.moying.iview.datasource

import ai.moying.iview.query.DataSourceRepository
import ai.moying.iview.query.DataSourceSecretCipher
import ai.moying.iview.query.DataSourceService
import ai.moying.iview.query.DatasetRepository
import ai.moying.iview.query.DatasetService
import ai.moying.iview.query.DataModelRepository
import ai.moying.iview.query.DataModelService
import ai.moying.iview.query.DashboardRepository
import ai.moying.iview.query.DashboardService
import ai.moying.iview.query.DashboardDocumentValidator
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

@Configuration
class DataSourceConfiguration {
    @Bean fun dataSourceService(repository: DataSourceRepository, cipher: DataSourceSecretCipher) = DataSourceService(repository, cipher)
    @Bean fun datasetService(repository: DatasetRepository, sources: DataSourceService) = DatasetService(repository, sources)
    @Bean fun dataModelService(repository: DataModelRepository, datasets: DatasetService) = DataModelService(repository, datasets)
    @Bean fun dashboardService(repository: DashboardRepository, models: DataModelService) = DashboardService(repository, models::get)
    @Bean fun dashboardDocumentValidator(models: DataModelService) = DashboardDocumentValidator(models::get)

    @Bean fun dataSourceSecretCipher(@Value("\${iview.data-source.secret}") secret: String): DataSourceSecretCipher = AesGcmDataSourceSecretCipher(secret)
}

class AesGcmDataSourceSecretCipher(secret: String) : DataSourceSecretCipher {
    private val key = SecretKeySpec(MessageDigest.getInstance("SHA-256").digest(secret.toByteArray(StandardCharsets.UTF_8)), "AES")
    private val random = SecureRandom()

    init { require(secret.length >= 16) { "iview.data-source.secret 至少需要16个字符" } }

    override fun encrypt(plainText: String): String {
        val nonce = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce))
        return "v1:${encode(nonce)}:${encode(cipher.doFinal(plainText.toByteArray(StandardCharsets.UTF_8)))}"
    }

    override fun decrypt(cipherText: String): String {
        val parts = cipherText.split(':')
        if (parts.size != 3 || parts[0] != "v1") throw IllegalArgumentException("数据源密码密文格式无效")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, decode(parts[1])))
        return String(cipher.doFinal(decode(parts[2])), StandardCharsets.UTF_8)
    }

    private fun encode(value: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(value)
    private fun decode(value: String) = Base64.getUrlDecoder().decode(value)
}
