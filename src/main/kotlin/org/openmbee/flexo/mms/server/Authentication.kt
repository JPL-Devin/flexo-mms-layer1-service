package org.openmbee.flexo.mms.server

import com.auth0.jwk.JwkProvider
import com.auth0.jwk.JwkProviderBuilder
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.http.auth.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import java.net.URI
import java.security.interfaces.RSAPublicKey
import java.util.concurrent.TimeUnit

fun Application.configureAuthentication() {
    authentication {
        jwt {
            val config = this@configureAuthentication.environment.config
            val jwtAudience = config.property("jwt.audience").getString()
            val issuer = config.property("jwt.domain").getString()
            val secret = config.property("jwt.secret").getString()
            realm = config.property("jwt.realm").getString()

            // legacy shared-secret verifier; remains the default and the fallback during RS256 migration
            val hmacVerifier = JWT.require(Algorithm.HMAC256(secret))
                .withAudience(jwtAudience)
                .withIssuer(issuer)
                .build()

            // optional JWKS endpoint (e.g. the sso-auth-service's /.well-known/jwks.json) enabling
            // asymmetric RS256 verification so that no service other than the token issuer needs to
            // hold key material capable of forging tokens
            val jwksUrl = config.propertyOrNull("jwt.jwksUrl")?.getString()
            val jwkProvider: JwkProvider? = jwksUrl?.takeIf { it.isNotBlank() }?.let {
                JwkProviderBuilder(URI.create(it).toURL())
                    .cached(10, 24, TimeUnit.HOURS)
                    .rateLimited(10, 1, TimeUnit.MINUTES)
                    .build()
            }

            // select the verifier per-token based on its header: RS256 tokens with a key id are
            // verified against the JWKS (when configured); everything else falls back to HMAC.
            // this permits both token types to coexist during a migration window.
            verifier { httpAuthHeader: HttpAuthHeader ->
                val token = (httpAuthHeader as? HttpAuthHeader.Single)?.blob
                    ?: return@verifier hmacVerifier

                val decoded = try {
                    JWT.decode(token)
                } catch(e: Exception) {
                    return@verifier hmacVerifier
                }

                if(jwkProvider != null && decoded.algorithm == "RS256" && decoded.keyId != null) {
                    try {
                        val publicKey = jwkProvider.get(decoded.keyId).publicKey as RSAPublicKey
                        JWT.require(Algorithm.RSA256(publicKey, null))
                            .withAudience(jwtAudience)
                            .withIssuer(issuer)
                            .build()
                    } catch(e: Exception) {
                        // JWKS fetch/key lookup failed; fall back so the request 401s instead of 500ing
                        this@configureAuthentication.log.warn("JWKS key lookup failed for kid=${decoded.keyId}: ${e.message}")
                        hmacVerifier
                    }
                } else {
                    hmacVerifier
                }
            }

            validate { credential ->
                if (credential.payload.audience.contains(jwtAudience)) {
                    UserDetailsPrincipal(
                        credential.payload.claims["username"]?.asString() ?: "",
                        credential.payload.claims["groups"]?.asList("".javaClass) ?: emptyList()
                    )
                } else null
            }
        }
    }
}

@Suppress("DEPRECATION")
data class UserDetailsPrincipal(val name: String, val groups: List<String>) : Principal
