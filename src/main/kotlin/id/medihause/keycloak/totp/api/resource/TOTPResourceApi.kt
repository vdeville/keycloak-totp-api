package id.medihause.keycloak.totp.api.resource

import id.medihause.keycloak.totp.api.dto.CommonApiResponse
import id.medihause.keycloak.totp.api.dto.GenerateTOTPResponse
import id.medihause.keycloak.totp.api.dto.RegisterTOTPCredentialRequest
import id.medihause.keycloak.totp.api.dto.VerifyTOTPRequest
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.GET
import jakarta.ws.rs.NotAuthorizedException
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.keycloak.events.EventBuilder
import org.keycloak.events.EventType
import org.keycloak.models.KeycloakSession
import org.keycloak.models.RealmModel
import org.keycloak.models.UserCredentialModel
import org.keycloak.models.UserModel
import org.keycloak.models.credential.OTPCredentialModel
import org.keycloak.models.utils.Base32
import org.keycloak.models.utils.HmacOTP
import org.keycloak.models.utils.TimeBasedOTP
import org.keycloak.services.managers.AppAuthManager
import org.keycloak.services.managers.BruteForceProtector
import org.keycloak.utils.CredentialHelper
import org.keycloak.utils.TotpUtils

class TOTPResourceApi(
    private val session: KeycloakSession,
) {

    private companion object {
        const val MANAGE_TOTP_ROLE = "manage-totp"
        const val PENDING_NAMESPACE = "totp-pending:"
        const val PENDING_TTL_SECONDS = 300L
        const val PENDING_FIELD_SECRET = "secret"

        // Audit event detail keys (cohérent avec les conventions Keycloak)
        const val DETAIL_DEVICE = "device"

        // Audit error codes
        const val ERR_USER_LOCKED = "user_temporarily_disabled"
        const val ERR_CREDENTIAL_NOT_FOUND = "credential_not_found"
        const val ERR_INVALID_CREDENTIALS = "invalid_user_credentials"
        const val ERR_UNKNOWN_PENDING = "unknown_pending_secret"
        const val ERR_INVALID_SECRET = "invalid_secret"
        const val ERR_CREDENTIAL_EXISTS = "credential_already_exists"
        const val ERR_CREATE_FAILED = "create_failed"
    }

    // -------- Helpers (responses) --------

    private fun jsonResponse(status: Response.Status, msg: String) =
        Response.status(status).entity(CommonApiResponse(msg)).build()

    private fun ok(msg: String) = jsonResponse(Response.Status.OK, msg)
    private fun created(msg: String) = jsonResponse(Response.Status.CREATED, msg)
    private fun badRequest(msg: String) = jsonResponse(Response.Status.BAD_REQUEST, msg)
    private fun notFound(msg: String = "Not found") = jsonResponse(Response.Status.NOT_FOUND, msg)
    private fun conflict(msg: String) = jsonResponse(Response.Status.CONFLICT, msg)
    private fun unauthorized(msg: String = "Unauthorized") = jsonResponse(Response.Status.UNAUTHORIZED, msg)
    private fun forbidden(msg: String) = jsonResponse(Response.Status.FORBIDDEN, msg)
    private fun tooManyRequests(msg: String) = jsonResponse(Response.Status.fromStatusCode(429), msg)
    private fun serverError(msg: String) = jsonResponse(Response.Status.INTERNAL_SERVER_ERROR, msg)

    // -------- Auth / User resolution --------

    private data class AuthContext(val realm: RealmModel, val user: UserModel, val callerClientId: String?)

    private fun authenticateAndResolveUser(userId: String): AuthContext {
        val auth = AppAuthManager.BearerTokenAuthenticator(session).authenticate()
            ?: throw NotAuthorizedException("Token not valid")

        // Only service accounts can use this API
        if (auth.user.serviceAccountClientLink == null) {
            throw NotAuthorizedException("User is not a service account")
        }

        val realmAccess = auth.token.realmAccess
        if (realmAccess == null || !realmAccess.isUserInRole(MANAGE_TOTP_ROLE)) {
            throw NotAuthorizedException("Forbidden")
        }

        val realm = session.context.realm

        // 404 générique pour éviter l'énumération (le caller a manage-totp donc le risque
        // est limité, mais on uniformise tout de même)
        val user = session.users().getUserById(realm, userId)
            ?: throw NotFoundResponse()

        if (user.serviceAccountClientLink != null) {
            throw ForbiddenResponse("Cannot manage service account")
        }

        return AuthContext(realm, user, auth.token.issuedFor)
    }

    private class NotFoundResponse : RuntimeException()
    private class ForbiddenResponse(message: String) : RuntimeException(message)

    private inline fun withAuth(userId: String, block: (AuthContext) -> Response): Response = try {
        block(authenticateAndResolveUser(userId))
    } catch (_: NotFoundResponse) {
        notFound()
    } catch (e: ForbiddenResponse) {
        forbidden(e.message ?: "Forbidden")
    }

    // -------- Audit events --------

    private fun newEvent(realm: RealmModel, callerClientId: String?): EventBuilder {
        val builder = EventBuilder(realm, session, session.context.connection)
        if (callerClientId != null) builder.client(callerClientId)
        return builder
    }

    // -------- OTP policy / secret length --------

    private fun secretLengthFromRealmPolicy(realm: RealmModel): Int {
        val algo = realm.otpPolicy.algorithm ?: "HmacSHA1"
        return when {
            algo.contains("SHA512", ignoreCase = true) -> 64
            algo.contains("SHA256", ignoreCase = true) -> 32
            else -> 20
        }
    }

    private fun pendingKey(userId: String) = "$PENDING_NAMESPACE$userId"

    // -------- Endpoints --------

    @GET
    @Path("/{userId}/generate")
    @Produces(MediaType.APPLICATION_JSON)
    fun generateTOTP(@PathParam("userId") userId: String): Response = withAuth(userId) { ctx ->
        val rawSecret = HmacOTP.generateSecret(secretLengthFromRealmPolicy(ctx.realm))
        val encodedSecret = Base32.encode(rawSecret.toByteArray(Charsets.UTF_8))
        val qrCode = TotpUtils.qrCode(rawSecret, ctx.realm, ctx.user)

        // Mémoriser le secret en attente pour ce user (TTL court) afin que
        // /register ne puisse accepter qu'un secret réellement émis par /generate.
        val singleUse = session.singleUseObjects()
        val key = pendingKey(userId)
        singleUse.remove(key)
        singleUse.put(key, PENDING_TTL_SECONDS, mapOf(PENDING_FIELD_SECRET to encodedSecret))

        Response.ok(GenerateTOTPResponse(encodedSecret = encodedSecret, qrCode = qrCode)).build()
    }

    @POST
    @Path("/{userId}/verify")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    fun verifyTOTP(request: VerifyTOTPRequest, @PathParam("userId") userId: String): Response = withAuth(userId) { ctx ->
        VerifyTOTPRequest.validate(request, ctx.realm.otpPolicy.digits)?.let { return@withAuth badRequest(it) }

        val event = newEvent(ctx.realm, ctx.callerClientId)
            .event(EventType.LOGIN)
            .user(ctx.user)
            .detail(DETAIL_DEVICE, request.deviceName)

        val brute = session.getProvider(BruteForceProtector::class.java)
        if (brute != null && (
                brute.isTemporarilyDisabled(session, ctx.realm, ctx.user) ||
                    brute.isPermanentlyLockedOut(session, ctx.realm, ctx.user)
                )
        ) {
            event.error(ERR_USER_LOCKED)
            return@withAuth tooManyRequests("User temporarily locked")
        }

        val credentialModel = ctx.user.credentialManager().getStoredCredentialByNameAndType(
            request.deviceName,
            OTPCredentialModel.TYPE
        ) ?: run {
            event.error(ERR_CREDENTIAL_NOT_FOUND)
            return@withAuth notFound()
        }

        val otpCredential = OTPCredentialModel.createFromCredentialModel(credentialModel)
        val isValid = ctx.user.credentialManager().isValid(
            UserCredentialModel(otpCredential.id, OTPCredentialModel.TYPE, request.code)
        )

        if (isValid) {
            brute?.successfulLogin(ctx.realm, ctx.user, session.context.connection, session.context.uri)
            event.success()
            ok("TOTP code is valid")
        } else {
            brute?.failedLogin(ctx.realm, ctx.user, session.context.connection, session.context.uri)
            event.error(ERR_INVALID_CREDENTIALS)
            unauthorized("Invalid TOTP code")
        }
    }

    @POST
    @Path("/{userId}/register")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    fun registerTOTP(request: RegisterTOTPCredentialRequest, @PathParam("userId") userId: String): Response =
        withAuth(userId) { ctx ->
            RegisterTOTPCredentialRequest.validate(request, ctx.realm.otpPolicy.digits)
                ?.let { return@withAuth badRequest(it) }

            val event = newEvent(ctx.realm, ctx.callerClientId)
                .event(EventType.UPDATE_TOTP)
                .user(ctx.user)
                .detail(DETAIL_DEVICE, request.deviceName)

            // Le secret DOIT correspondre à un secret émis récemment par /generate
            // pour ce user. Bloque l'injection d'un secret arbitraire connu de l'appelant.
            val singleUse = session.singleUseObjects()
            val key = pendingKey(userId)
            val pending = singleUse.get(key)
            if (pending == null || pending[PENDING_FIELD_SECRET] != request.encodedSecret) {
                event.error(ERR_UNKNOWN_PENDING)
                return@withAuth badRequest("No matching pending TOTP secret. Call /generate first.")
            }

            val secretBytes: ByteArray = try {
                Base32.decode(request.encodedSecret)
            } catch (_: Exception) {
                event.error(ERR_INVALID_SECRET)
                return@withAuth badRequest("Invalid secret")
            }

            try {
                val existingCredential = ctx.user.credentialManager()
                    .getStoredCredentialByNameAndType(request.deviceName, OTPCredentialModel.TYPE)

                if (existingCredential != null && !request.overwrite) {
                    event.error(ERR_CREDENTIAL_EXISTS)
                    return@withAuth conflict("TOTP credential already exists")
                }

                val totp = TimeBasedOTP(
                    ctx.realm.otpPolicy.algorithm,
                    ctx.realm.otpPolicy.digits,
                    ctx.realm.otpPolicy.period,
                    ctx.realm.otpPolicy.lookAheadWindow
                )

                if (!totp.validateTOTP(request.initialCode, secretBytes)) {
                    event.error(ERR_INVALID_CREDENTIALS)
                    return@withAuth badRequest("Invalid Initial TOTP")
                }

                // createFromPolicy attend une String (limite API Keycloak) — on minimise
                // sa durée de vie en ne la stockant pas dans une variable nommée.
                val totpCredentialModel = OTPCredentialModel.createFromPolicy(
                    ctx.realm,
                    String(secretBytes, Charsets.UTF_8),
                    request.deviceName
                )

                if (existingCredential != null && request.overwrite) {
                    ctx.user.credentialManager().removeStoredCredentialById(existingCredential.id)
                    // Event distinct REMOVE_TOTP pour traçabilité audit
                    newEvent(ctx.realm, ctx.callerClientId)
                        .event(EventType.REMOVE_TOTP)
                        .user(ctx.user)
                        .detail(DETAIL_DEVICE, request.deviceName)
                        .success()
                }

                val createdOk = CredentialHelper.createOTPCredential(
                    session, ctx.realm, ctx.user, request.initialCode, totpCredentialModel
                )

                // Keycloak peut retourner false alors que le credential a bien été créé.
                val effectivelyCreated = createdOk || ctx.user.credentialManager()
                    .getStoredCredentialByNameAndType(request.deviceName, OTPCredentialModel.TYPE) != null

                if (!effectivelyCreated) {
                    event.error(ERR_CREATE_FAILED)
                    return@withAuth serverError("Failed to create TOTP credential")
                }

                // Invalide le secret en attente : un seul register par /generate.
                singleUse.remove(key)
                event.success()
                created("TOTP credential registered")
            } finally {
                secretBytes.fill(0)
            }
        }
}
