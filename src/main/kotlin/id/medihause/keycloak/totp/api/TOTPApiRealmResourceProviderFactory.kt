package id.medihause.keycloak.totp.api

import org.keycloak.Config
import org.keycloak.models.KeycloakSession
import org.keycloak.models.KeycloakSessionFactory
import org.keycloak.services.resource.RealmResourceProvider
import org.keycloak.services.resource.RealmResourceProviderFactory

class TOTPApiRealmResourceProviderFactory : RealmResourceProviderFactory {
    companion object {
        const val PROVIDER_ID = "totp-api"
    }

    override fun create(session: KeycloakSession): RealmResourceProvider =
        TOTPApiRealmResourceProvider(session)

    override fun init(config: Config.Scope) {}
    override fun postInit(factory: KeycloakSessionFactory) {}
    override fun close() {}
    override fun getId(): String = PROVIDER_ID
}
