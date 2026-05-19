package id.medihause.keycloak.totp.api.dto

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class RegisterTOTPCredentialRequestTest {

    private val validBase32 = "OFIWESBQGBLFG432HB5G6TTLIVIEGU2O"

    private fun req(
        deviceName: String = "DeviceOne",
        encodedSecret: String = validBase32,
        initialCode: String = "123456",
        overwrite: Boolean = false,
    ) = RegisterTOTPCredentialRequest(deviceName, encodedSecret, initialCode, overwrite)

    @Test
    fun `valid request returns null`() {
        assertNull(RegisterTOTPCredentialRequest.validate(req(), expectedDigits = 6))
    }

    @Test
    fun `lowercase secret rejected (not base32)`() {
        val r = req(encodedSecret = validBase32.lowercase())
        assertEquals("Invalid encodedSecret format", RegisterTOTPCredentialRequest.validate(r, expectedDigits = 6))
    }

    @Test
    fun `empty secret rejected`() {
        val r = req(encodedSecret = "")
        assertEquals("Invalid encodedSecret length", RegisterTOTPCredentialRequest.validate(r, expectedDigits = 6))
    }

    @Test
    fun `secret over max length rejected`() {
        val r = req(encodedSecret = "A".repeat(RegisterTOTPCredentialRequest.MAX_SECRET_LENGTH + 1))
        assertEquals("Invalid encodedSecret length", RegisterTOTPCredentialRequest.validate(r, expectedDigits = 6))
    }

    @Test
    fun `secret with padding accepted`() {
        val r = req(encodedSecret = "OFIWESBQGBLFG432HB5G6TTLIVIEGU2O====")
        assertNull(RegisterTOTPCredentialRequest.validate(r, expectedDigits = 6))
    }

    @Test
    fun `initialCode wrong length rejected`() {
        val r = req(initialCode = "12345")
        assertEquals("Invalid initialCode format", RegisterTOTPCredentialRequest.validate(r, expectedDigits = 6))
    }

    @Test
    fun `initialCode non-digit rejected`() {
        val r = req(initialCode = "1234a6")
        assertEquals("Invalid initialCode format", RegisterTOTPCredentialRequest.validate(r, expectedDigits = 6))
    }

    @Test
    fun `8-digit policy accepts 8-digit code`() {
        val r = req(initialCode = "12345678")
        assertNull(RegisterTOTPCredentialRequest.validate(r, expectedDigits = 8))
    }

    @Test
    fun `deviceName with forbidden char rejected`() {
        val r = req(deviceName = "Device/Two")
        assertEquals("Invalid deviceName", RegisterTOTPCredentialRequest.validate(r, expectedDigits = 6))
    }

    @Test
    fun `deviceName at max length accepted`() {
        val name = "a".repeat(RegisterTOTPCredentialRequest.MAX_DEVICE_NAME_LENGTH)
        val r = req(deviceName = name)
        assertNotNull(name) // sanity
        assertNull(RegisterTOTPCredentialRequest.validate(r, expectedDigits = 6))
    }
}
