package me.alllexey123.itmowidgets.backend

import com.google.auth.oauth2.GoogleCredentials
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.io.ClassPathResource

@Configuration
class FirebaseConfig {

    @Bean
    fun initializeFirebase(): FirebaseApp {
        val accountCredentials = ClassPathResource("itmo-widgets-firebase.json").inputStream
        val options = FirebaseOptions.builder()
            .setCredentials(GoogleCredentials.fromStream(accountCredentials))
            .build()

        return if (FirebaseApp.getApps().isEmpty()) {
            FirebaseApp.initializeApp(options)
        } else {
            FirebaseApp.getInstance()
        }
    }
}