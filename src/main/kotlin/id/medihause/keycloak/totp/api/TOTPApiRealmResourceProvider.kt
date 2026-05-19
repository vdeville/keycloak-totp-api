package id.medihause.keycloak.totp.api

import id.medihause.keycloak.totp.api.resource.TOTPResourceApi
import org.keycloak.models.KeycloakSession
import org.keycloak.services.resource.RealmResourceProvider

class TOTPApiRealmResourceProvider(session: KeycloakSession) : RealmResourceProvider {
    private val resource = TOTPResourceApi(session)

    override fun close() {}
    override fun getResource(): TOTPResourceApi = resource
}
