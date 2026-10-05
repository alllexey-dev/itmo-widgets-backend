package dev.alllexey.itmowidgets.backend.platform.security

import com.auth0.jwk.InvalidPublicKeyException
import com.auth0.jwk.JwkProvider
import com.auth0.jwk.JwkProviderBuilder
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.interfaces.Claim
import com.auth0.jwt.interfaces.DecodedJWT
import jakarta.annotation.PostConstruct
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.net.URI
import java.security.interfaces.RSAPublicKey
import java.util.concurrent.TimeUnit

@Service
class ItmoJwtVerifier(private val clients: ItmoClientPolicy) {

    @Value($$"${id.itmo.jwks-url}")
    private lateinit var jwksUrl: String

    @Value($$"${id.itmo.issuer}")
    private lateinit var issuer: String

    private lateinit var jwkProvider: JwkProvider

    @PostConstruct
    fun init() {
        this.jwkProvider = JwkProviderBuilder(URI(jwksUrl).toURL())
            .cached(10, 7, TimeUnit.DAYS)
            .build()
    }

    /**
     * A bearer access token: [verifyAndDecode], then [ItmoClientPolicy] counts its client (`azp`) and, when enforcing,
     * rejects it with a [com.auth0.jwt.exceptions.JWTVerificationException].
     */
    fun verifyAccessToken(token: String): DecodedJWT = verifyAndDecode(token).also(clients::check)

    /**
     * The verified token. A token that authenticates nobody fails with a [com.auth0.jwt.exceptions.JWTVerificationException]
     * (malformed, bad signature, expired, wrong issuer) or a [com.auth0.jwk.JwkException]
     * (unknown key id, the key set unreachable); nothing else means a bad token.
     */
    fun verifyAndDecode(token: String): DecodedJWT {
        val decodedJWT = JWT.decode(token)
        val keyId = decodedJWT.keyId
        val jwk = jwkProvider.get(keyId)
        val publicKey = jwk.publicKey as? RSAPublicKey ?: throw InvalidPublicKeyException("The signing key is not an RSA key")
        val algo = Algorithm.RSA256(publicKey, null)
        val verifier = JWT.require(algo)
            .withIssuer(issuer)
            .acceptLeeway(60)
            .build()

        return verifier.verify(decodedJWT)
    }

    companion object {
        fun DecodedJWT.getIsu(): Int? = getClaimOrNull("isu")?.asInt()

        fun DecodedJWT.getClaimOrNull(claimName: String): Claim? = if (claims.contains(claimName)) getClaim(claimName) else null
    }
}
