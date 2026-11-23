package dev.alllexey.itmowidgets.backend.feature.push.service

import com.google.api.client.http.javanet.NetHttpTransport
import com.google.auth.oauth2.GoogleCredentials
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.io.FileInputStream

/** Without a service-account key (blank `firebase.key.path`) there is no Firebase and [FcmService] only logs. */
@Configuration
@ConditionalOnExpression("!'\${firebase.key.path:}'.isBlank()")
class FirebaseConfig {

    @Value("\${firebase.key.path}")
    private lateinit var firebaseKeyPath: String

    @Bean
    fun initializeFirebase(): FirebaseApp {
        val credentials = FileInputStream(firebaseKeyPath).use { GoogleCredentials.fromStream(it) }
        val options = firebaseOptions(credentials)

        return if (FirebaseApp.getApps().isEmpty()) {
            FirebaseApp.initializeApp(options)
        } else {
            FirebaseApp.getInstance()
        }
    }

    @Bean
    fun firebaseMessaging(firebaseApp: FirebaseApp): FirebaseMessaging = FirebaseMessaging.getInstance(firebaseApp)
}

/**
 * Firebase over the JDK's `HttpURLConnection`. Its default `ApacheHttp2Transport` runs on the Apache HttpClient 5 that
 * Spring Boot manages; since 5.6 that client already decompresses a gzip answer, and `google-http-client` then fails to
 * gunzip it again with `ZipException: Not in GZIP format`, so every FCM send failed (`FirebaseTransportTest`).
 */
internal fun firebaseOptions(credentials: GoogleCredentials): FirebaseOptions = FirebaseOptions.builder()
    .setCredentials(credentials)
    .setHttpTransport(NetHttpTransport())
    .build()
