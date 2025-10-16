package me.alllexey123.itmowidgets.backend.configs

import com.google.auth.oauth2.GoogleCredentials
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.io.FileInputStream

@Configuration
class FirebaseConfig {

    @Value("\${firebase.key.path}")
    private var firebaseKeyPath: String? = null

    @Bean
    fun initializeFirebase(): FirebaseApp {
        val accountCredentials = FileInputStream(firebaseKeyPath!!)
        val options = FirebaseOptions.builder()
            .setCredentials(GoogleCredentials.fromStream(accountCredentials))
            .build()

        return if (FirebaseApp.getApps().isEmpty()) {
            FirebaseApp.initializeApp(options)
        } else {
            FirebaseApp.getInstance()
        }
    }

    @Bean
    fun firebaseMessaging(firebaseApp: FirebaseApp): FirebaseMessaging {
        return FirebaseMessaging.getInstance(firebaseApp)
    }
}