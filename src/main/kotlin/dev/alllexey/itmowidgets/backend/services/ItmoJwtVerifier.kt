package dev.alllexey.itmowidgets.backend.services

import com.auth0.jwk.JwkProvider
import com.auth0.jwk.JwkProviderBuilder
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.interfaces.DecodedJWT
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.net.URI
import java.security.interfaces.RSAPublicKey
import java.util.concurrent.TimeUnit
import javax.annotation.PostConstruct

@Service
class ItmoJwtVerifier {

    @Value("\${id.itmo.jwks-url}")
    private lateinit var jwksUrl: String

    @Value("\${id.itmo.issuer}")
    private lateinit var issuer: String

    private lateinit var jwkProvider: JwkProvider

    @PostConstruct
    fun init() {
        this.jwkProvider = JwkProviderBuilder(URI(jwksUrl).toURL())
            .cached(10, 7, TimeUnit.DAYS)
            .build()
    }

    fun verifyAndDecode(token: String): DecodedJWT {
        val decodedJWT = JWT.decode(token)
        val keyId = decodedJWT.keyId
        val jwk = jwkProvider.get(keyId)
        val algo = Algorithm.RSA256(jwk.publicKey as RSAPublicKey, null)
        val verifier = JWT.require(algo)
            .withIssuer(issuer)
            .acceptLeeway(60)
            .build()

        return verifier.verify(decodedJWT)
    }

    fun getIsu(decodedJWT: DecodedJWT): Int? {
        return if (decodedJWT.claims.contains("isu")) decodedJWT.getClaim("isu").asInt() else null
    }
}