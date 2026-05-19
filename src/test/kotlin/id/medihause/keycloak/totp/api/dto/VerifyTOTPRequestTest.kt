package id.medihause.keycloak.totp.api.dto

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class VerifyTOTPRequestTest {

    @Test
    fun `valid request returns null`() {
        val req = VerifyTOTPRequest(deviceName = "DeviceOne", code = "123456")
        assertNull(VerifyTOTPRequest.validate(req, expectedDigits = 6))
    }

    @Test
    fun `non-digit code rejected`() {
        val req = VerifyTOTPRequest(deviceName = "DeviceOne", code = "12a456")
        assertEquals("Invalid code format", VerifyTOTPRequest.validate(req, expectedDigits = 6))
    }

    @Test
    fun `wrong length code rejected`() {
        val req = VerifyTOTPRequest(deviceName = "DeviceOne", code = "12345")
        assertEquals("Invalid code format", VerifyTOTPRequest.validate(req, expectedDigits = 6))
    }

    @Test
    fun `8-digit code accepted when policy is 8`() {
        val req = VerifyTOTPRequest(deviceName = "DeviceOne", code = "12345678")
        assertNull(VerifyTOTPRequest.validate(req, expectedDigits = 8))
    }

    @Test
    fun `empty deviceName rejected`() {
        val req = VerifyTOTPRequest(deviceName = "", code = "123456")
        assertEquals("Invalid deviceName", VerifyTOTPRequest.validate(req, expectedDigits = 6))
    }

    @Test
    fun `deviceName with forbidden char rejected`() {
        val req = VerifyTOTPRequest(deviceName = "Device<script>", code = "123456")
        assertNotNull(VerifyTOTPRequest.validate(req, expectedDigits = 6))
    }

    @Test
    fun `deviceName over max length rejected`() {
        val name = "a".repeat(VerifyTOTPRequest.MAX_DEVICE_NAME_LENGTH + 1)
        val req = VerifyTOTPRequest(deviceName = name, code = "123456")
        assertEquals("Invalid deviceName", VerifyTOTPRequest.validate(req, expectedDigits = 6))
    }
}
