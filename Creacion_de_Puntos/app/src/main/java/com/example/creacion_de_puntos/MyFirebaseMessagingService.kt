package com.example.creacion_de_puntos

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.location.Location
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.*
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.*

class MyFirebaseMessagingService : FirebaseMessagingService() {

    companion object {
        private const val TAG = "FCMService"
        private const val CHANNEL_ID = "FCMChannel"
        private const val NOTIFICATION_ID = 2
        private const val PREFS_NAME = "TrackingPrefs"
        private const val KEY_TRACKING_ACTIVE = "isTrackingActive"
        private const val KEY_FILENAME = "currentFilename"
    }

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var sharedPrefs: SharedPreferences

    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        sharedPrefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        createNotificationChannel()
    }

    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)

        Log.d(TAG, "Push recibido: ${remoteMessage.data}")

        if (!sharedPrefs.getBoolean(KEY_TRACKING_ACTIVE, false)) {
            Log.d(TAG, "Tracking inactivo, ignorando push")
            return
        }

        val command = remoteMessage.data["command"]
        if (command == "ejecutar_tarea") {
            Log.d(TAG, "Command 'ejecutar_tarea' recibido, guardando ubicación")

            if (!hasLocationPermissions()) {
                Log.e(TAG, "Permisos de ubicación no concedidos")
                return
            }

            fusedLocationClient.lastLocation.addOnSuccessListener { location: Location? ->
                if (location != null) {
                    guardarUbicacion(location)
                    Log.d(TAG, "Ubicación guardada vía push")
                } else {
                    requestSingleLocationUpdateAndSave()
                }
            }.addOnFailureListener {
                Log.e(TAG, "Error obteniendo ubicación: ${it.message}")
                requestSingleLocationUpdateAndSave()
            }

            showNotification("Ubicación guardada", "Orden ejecutada vía push")
        }
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d(TAG, "Nuevo token FCM: $token")
    }

    private fun hasLocationPermissions(): Boolean {
        val hasFine = ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasBackground = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
        } else true
        return hasFine && hasBackground
    }

    private fun requestSingleLocationUpdateAndSave() {
        val locationRequest = LocationRequest.create().apply {
            priority = LocationRequest.PRIORITY_HIGH_ACCURACY
        }
        val locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                locationResult.lastLocation?.let { location ->
                    guardarUbicacion(location)
                    fusedLocationClient.removeLocationUpdates(this)
                }
            }
        }
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())
    }

    private fun guardarUbicacion(location: Location) {
        val filename = sharedPrefs.getString(KEY_FILENAME, "ubicaciones.json") ?: "ubicaciones.json"
        val jsonObject = JSONObject().apply {
            put("lat", location.latitude)
            put("lon", location.longitude)
            put("timestamp", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()))
            put("source", "push")
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            guardarConMediaStore(jsonObject, filename)
        } else {
            guardarConFileApi(jsonObject, filename)
        }
    }

    private fun guardarConFileApi(jsonObject: JSONObject, filename: String) {
        try {
            val documentsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
            val appDir = File(documentsDir, "Creacion_de_Puntos")
            if (!appDir.exists()) appDir.mkdirs()

            val file = File(appDir, filename)
            val existingArray = if (file.exists() && file.length() > 0) {
                try {
                    val text = file.readText()
                    Log.d(TAG, "FileAPI: Leyendo archivo existente, tamaño: ${file.length()} bytes")
                    JSONArray(text)
                } catch (e: Exception) {
                    Log.e(TAG, "FileAPI: Error parsing JSON: ${e.message}")
                    JSONArray()
                }
            } else {
                Log.d(TAG, "FileAPI: Archivo no existe, creando nuevo")
                JSONArray()
            }

            val initialLength = existingArray.length()
            existingArray.put(jsonObject)
            Log.d(TAG, "FileAPI: Append exitoso. Antes: $initialLength, después: ${existingArray.length()}")

            FileWriter(file).use { it.write(existingArray.toString(2)) }
            Log.i(TAG, "Ubicación guardada (FileAPI) en ${file.absolutePath}, total: ${existingArray.length()}")
        } catch (e: Exception) {
            Log.e(TAG, "Error (FileAPI): ${e.message}", e)
        }
    }

    // FIXED: Persistir Uri en SharedPrefs para evitar re-inserts
    // FIXED: Persistir Uri en SharedPrefs para evitar re-inserts + safe calls para nullable Uri
    private fun guardarConMediaStore(jsonObject: JSONObject, filename: String) {
        val relativePath = Environment.DIRECTORY_DOCUMENTS + File.separator + "Creacion_de_Puntos"
        val prefsKey = "file_uri_$filename"  // Clave única por filename (mantiene esquema custom)

        // Intenta obtener Uri persistido
        val savedUriString = sharedPrefs.getString(prefsKey, null)
        var fileUri: Uri? = savedUriString?.let { Uri.parse(it) }

        if (fileUri == null) {
            // Fallback: Query por nombre/path
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            }

            val queryUri = MediaStore.Files.getContentUri("external")
            val projection = arrayOf(MediaStore.Files.FileColumns._ID)
            val selection = "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND ${MediaStore.MediaColumns.DISPLAY_NAME} = ?"
            val selectionArgs = arrayOf(relativePath, filename)

            contentResolver.query(queryUri, projection, selection, selectionArgs, null)?.use {
                if (it.moveToFirst()) {
                    val id = it.getLong(it.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID))
                    fileUri = ContentUris.withAppendedId(queryUri, id)
                    Log.d(TAG, "MediaStore: Archivo encontrado con ID persistido: $id")
                } else {
                    Log.d(TAG, "MediaStore: No encontrado, insertando nuevo")
                    fileUri = contentResolver.insert(queryUri, contentValues)
                    if (fileUri != null) {
                        // Guarda Uri para futuros appends
                        sharedPrefs.edit().putString(prefsKey, fileUri.toString()).apply()
                        Log.d(TAG, "MediaStore: Insertado y persistido Uri: $fileUri")
                    } else {
                        Log.e(TAG, "MediaStore: Falló insert")
                        return
                    }
                }
            } ?: run {
                Log.e(TAG, "MediaStore: Query falló")
                return
            }
        } else {
            Log.d(TAG, "MediaStore: Usando Uri persistido: $fileUri")
        }

        // FIXED: Variable local no-nullable después de checks
        val uri = fileUri ?: return  // Si null aquí, sale temprano

        try {
            val existingArray = try {
                contentResolver.openInputStream(uri)?.bufferedReader()?.readText()?.let { text ->
                    Log.d(TAG, "MediaStore: Leyendo desde Uri, longitud: ${text.length}")
                    JSONArray(text)
                } ?: run {
                    Log.d(TAG, "MediaStore: InputStream null, array vacío")
                    JSONArray()
                }
            } catch (e: Exception) {
                Log.e(TAG, "MediaStore: Error parsing JSON: ${e.message}")
                JSONArray()
            }

            val initialLength = existingArray.length()
            existingArray.put(jsonObject)
            Log.d(TAG, "MediaStore: Append exitoso. Antes: $initialLength, después: ${existingArray.length()}")

            contentResolver.openOutputStream(uri, "w")?.use { output ->
                output.write(existingArray.toString(2).toByteArray())
            } ?: run {
                Log.e(TAG, "MediaStore: Falló openOutputStream")
                return
            }

            Log.i(TAG, "Ubicación guardada (MediaStore) en $uri, total: ${existingArray.length()}")

        } catch (e: Exception) {
            Log.e(TAG, "Error (MediaStore): ${e.message}", e)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Canal de Notificaciones FCM",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notificaciones para órdenes de tracking vía push"
            }
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun showNotification(title: String, body: String) {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(body)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(pendingIntent)
            .build()

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }
}