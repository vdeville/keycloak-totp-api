package id.medihause.keycloak.totp.api.dto

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.annotation.JsonSerialize

@JsonSerialize
data class RegisterTOTPCredentialRequest(
    @JsonProperty("deviceName")
    val deviceName: String,

    @JsonProperty("encodedSecret")
    val encodedSecret: String,

    @JsonProperty("initialCode")
    val initialCode: String,

    @JsonProperty("overwrite")
    val overwrite: Boolean = false
) {
    companion object {
        const val MAX_DEVICE_NAME_LENGTH = 128
        const val MAX_SECRET_LENGTH = 256
        private val DEVICE_NAME_REGEX = Regex("^[\\w.\\- ]{1,$MAX_DEVICE_NAME_LENGTH}$")
        private val BASE32_REGEX = Regex("^[A-Z2-7]+=*$")

        fun validate(request: RegisterTOTPCredentialRequest, expectedDigits: Int): String? {
            if (!DEVICE_NAME_REGEX.matches(request.deviceName)) {
                return "Invalid deviceName"
            }
            if (request.encodedSecret.isEmpty() || request.encodedSecret.length > MAX_SECRET_LENGTH) {
                return "Invalid encodedSecret length"
            }
            if (!BASE32_REGEX.matches(request.encodedSecret)) {
                return "Invalid encodedSecret format"
            }
            if (request.initialCode.length != expectedDigits || !request.initialCode.all(Char::isDigit)) {
                return "Invalid initialCode format"
            }
            return null
        }
    }
}
