package com.example.creacion_de_puntos

import android.app.Application
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging

class MyApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        // Inicializa Firebase aquí (se ejecuta al inicio de la app)
        FirebaseApp.initializeApp(this)

        // Opcional: Obtén token FCM globalmente (no en Activity)
        try {
            FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    val token = task.result
                    Log.d("FCM_TOKEN", "Token FCM inicializado en Application: $token")
                    // Opcional: Guarda en SharedPrefs para usar después
                } else {
                    Log.e("FCM_TOKEN", "Error en token desde Application: ${task.exception?.message}", task.exception)
                }
            }
        } catch (e: Exception) {
            Log.e("FCM_INIT", "Error inicializando FCM en Application: ${e.message}", e)
        }
    }
}