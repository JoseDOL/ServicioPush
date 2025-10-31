package com.example.creacion_de_puntos

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var etIntervalo: EditText  // Mantener por compatibilidad, pero no se usa
    private lateinit var etArchivo: EditText
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var btnElegirUbicacion: Button

    private val PERMISSIONS_REQUEST_CODE_FINE_STORAGE = 1
    private val PERMISSIONS_REQUEST_CODE_BACKGROUND = 2
    private var nombreArchivo: String = "ubicaciones.json"
    private lateinit var sharedPrefs: SharedPreferences

    companion object {
        private const val PREFS_NAME = "TrackingPrefs"
        private const val KEY_TRACKING_ACTIVE = "isTrackingActive"
        private const val KEY_FILENAME = "currentFilename"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        sharedPrefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        // Inicializar vistas
        tvStatus = findViewById(R.id.tvStatus)
        etIntervalo = findViewById(R.id.etIntervalo)
        etArchivo = findViewById(R.id.etArchivo)
        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)
        btnElegirUbicacion = findViewById(R.id.btnElegirUbicacion)

        // Ocultar botón no necesario
        btnElegirUbicacion.visibility = View.GONE
        tvStatus.text = "Ubicación: ${obtenerNombreUbicacion()}"

        // Cargar estado inicial
        actualizarUIDesdePrefs()

        // Evento del botón Iniciar
        btnStart.setOnClickListener {
            solicitarPermisosPaso1()
        }

        // Evento del botón Detener
        btnStop.setOnClickListener {
            detenerTracking()
        }
    }

    private fun obtenerNombreUbicacion(): String {
        return "Documents/Creacion_de_Puntos"
    }

    private fun configurarParametros(): Boolean {
        try {
            // Intervalo no se usa más, pero validar por UI
            etIntervalo.text.toString().toLongOrNull()?.let { segundos ->
                if (segundos <= 0) {
                    Toast.makeText(this, "El intervalo debe ser mayor a 0 segundos.", Toast.LENGTH_SHORT).show()
                    return false
                }
            } ?: run {
                Toast.makeText(this, "Ingresa un número válido para el intervalo.", Toast.LENGTH_SHORT).show()
                return false
            }

            nombreArchivo = etArchivo.text.toString().ifEmpty { "ubicaciones.json" }
            return true
        } catch (e: NumberFormatException) {
            Toast.makeText(this, "Ingresa un número válido.", Toast.LENGTH_SHORT).show()
            return false
        }
    }

    // Mismo flujo de permisos (Paso 1 y 2), pero al final activa tracking
    private fun solicitarPermisosPaso1() {
        val permissionsToRequest = mutableListOf<String>()
        permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION)

        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            permissionsToRequest.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }

        val hasFineLocation = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            val hasStorage = ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
            if (hasFineLocation && hasStorage) {
                verificarYPasarPaso2()
                return
            }
        } else {
            if (hasFineLocation) {
                verificarYPasarPaso2()
                return
            }
        }

        Toast.makeText(this, "Solicitando permisos de ubicación y almacenamiento...", Toast.LENGTH_SHORT).show()
        ActivityCompat.requestPermissions(this, permissionsToRequest.toTypedArray(), PERMISSIONS_REQUEST_CODE_FINE_STORAGE)
    }

    private fun verificarYPasarPaso2() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            solicitarPermisosPaso2()
        } else {
            if (configurarParametros()) {
                iniciarTracking()
            }
        }
    }

    private fun solicitarPermisosPaso2() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            if (configurarParametros()) {
                iniciarTracking()
            }
            return
        }

        Toast.makeText(this, "Para tracking vía push en segundo plano, permite 'Ubicación precisa en segundo plano' en Ajustes.", Toast.LENGTH_LONG).show()
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), PERMISSIONS_REQUEST_CODE_BACKGROUND)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            PERMISSIONS_REQUEST_CODE_FINE_STORAGE -> {
                var allGranted = true
                for (i in permissions.indices) {
                    val permission = permissions[i]
                    val granted = grantResults[i] == PackageManager.PERMISSION_GRANTED
                    if (!granted) {
                        allGranted = false
                        when (permission) {
                            Manifest.permission.ACCESS_FINE_LOCATION -> {
                                Toast.makeText(this, "Permiso de ubicación DENEGADO. No se puede continuar.", Toast.LENGTH_LONG).show()
                            }
                            Manifest.permission.WRITE_EXTERNAL_STORAGE -> {
                                Toast.makeText(this, "Permiso de almacenamiento DENEGADO. Actívalo en Ajustes.", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                }
                if (allGranted) {
                    verificarYPasarPaso2()
                } else {
                    Toast.makeText(this, "Permisos requeridos. Intenta de nuevo.", Toast.LENGTH_LONG).show()
                }
            }
            PERMISSIONS_REQUEST_CODE_BACKGROUND -> {
                val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
                if (granted) {
                    Toast.makeText(this, "Permiso de background concedido. Iniciando tracking...", Toast.LENGTH_SHORT).show()
                    if (configurarParametros()) {
                        iniciarTracking()
                    }
                } else {
                    Toast.makeText(this, "Permiso de background DENEGADO. Tracking funcionará solo en foreground. Actívalo en Ajustes.", Toast.LENGTH_LONG).show()
                    if (configurarParametros()) {
                        iniciarTracking()  // Inicia de todos modos, pero con limitaciones
                    }
                }
            }
        }
    }

    private fun iniciarTracking() {
        if (sharedPrefs.getBoolean(KEY_TRACKING_ACTIVE, false)) {
            Toast.makeText(this, "Tracking ya activo.", Toast.LENGTH_SHORT).show()
            return
        }

        // Guardar flag y filename
        sharedPrefs.edit().apply {
            putBoolean(KEY_TRACKING_ACTIVE, true)
            putString(KEY_FILENAME, nombreArchivo)
            apply()
        }

        actualizarUI()
        Toast.makeText(this, "Tracking activado. Esperando pushes para guardar ubicaciones.", Toast.LENGTH_SHORT).show()

        // Opcional: Obtener token FCM y loguearlo (para copiar al .NET)
        // FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
        //     if (task.isSuccessful) Log.d("FCM", "Token: ${task.result}")
        // }
    }

    private fun detenerTracking() {
        if (!sharedPrefs.getBoolean(KEY_TRACKING_ACTIVE, false)) {
            Toast.makeText(this, "Tracking no activo.", Toast.LENGTH_SHORT).show()
            return
        }

        sharedPrefs.edit().apply {
            putBoolean(KEY_TRACKING_ACTIVE, false)
            apply()
        }

        actualizarUI()
        Toast.makeText(this, "Tracking detenido. Pushes serán ignorados.", Toast.LENGTH_SHORT).show()
    }

    private fun actualizarUI() {
        val isActive = sharedPrefs.getBoolean(KEY_TRACKING_ACTIVE, false)
        tvStatus.text = if (isActive) {
            "Estado: Tracking ACTIVO | Esperando pushes | Archivo: $nombreArchivo"
        } else {
            "Estado: Detenido | Ubicación: ${obtenerNombreUbicacion()}"
        }
        btnStart.isEnabled = !isActive
        btnStop.isEnabled = isActive
    }

    private fun actualizarUIDesdePrefs() {
        nombreArchivo = sharedPrefs.getString(KEY_FILENAME, "ubicaciones.json") ?: "ubicaciones.json"
        etArchivo.setText(nombreArchivo)
        actualizarUI()
    }

    override fun onDestroy() {
        super.onDestroy()
        // No detiene automáticamente; el usuario maneja con botón
    }
}