package id.medihause.keycloak.totp.api.dto

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.annotation.JsonSerialize

@JsonSerialize
data class VerifyTOTPRequest(
    @JsonProperty("deviceName")
    val deviceName: String,

    @JsonProperty("code")
    val code: String
) {
    companion object {
        const val MAX_DEVICE_NAME_LENGTH = 128
        private val DEVICE_NAME_REGEX = Regex("^[\\w.\\- ]{1,$MAX_DEVICE_NAME_LENGTH}$")

        fun validate(request: VerifyTOTPRequest, expectedDigits: Int): String? {
            if (!DEVICE_NAME_REGEX.matches(request.deviceName)) {
                return "Invalid deviceName"
            }
            if (request.code.length != expectedDigits || !request.code.all(Char::isDigit)) {
                return "Invalid code format"
            }
            return null
        }
    }
}
