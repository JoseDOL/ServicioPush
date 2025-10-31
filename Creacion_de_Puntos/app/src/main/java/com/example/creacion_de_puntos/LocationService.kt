package com.example.creacion_de_puntos

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.*

class LocationService : Service() {

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback

    private var nombreArchivo: String = "ubicaciones.json"

    companion object {
        const val CHANNEL_ID = "LocationServiceChannel"
        const val NOTIFICATION_ID = 1
        private const val TAG = "LocationService"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                locationResult.lastLocation?.let {
                    guardarUbicacion(it)
                    fusedLocationClient.removeLocationUpdates(this)
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        nombreArchivo = intent?.getStringExtra("filename") ?: "ubicaciones.json"

        val batteryLevel = getBatteryLevel()
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        val logMessage = "Inicio de registro: $timestamp - Batería: $batteryLevel%\n"
        saveBatteryLog(logMessage)

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Permisos no concedidos en servicio. Deteniendo.")
            stopSelf()
            return START_NOT_STICKY
        }

        obtenerUbicacionUnica()

        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Registro de Ubicaciones")
            .setContentText("Servicio activo. Guardando puntos a demanda.")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()

        startForeground(NOTIFICATION_ID, notification)
        Log.i(TAG, "Servicio iniciado")
        return START_STICKY
    }

    private fun obtenerUbicacionUnica() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        fusedLocationClient.lastLocation.addOnSuccessListener { location: Location? ->
            if (location != null) {
                guardarUbicacion(location)
                Log.i(TAG, "Ubicación inicial guardada")
            } else {
                fusedLocationClient.requestLocationUpdates(
                    LocationRequest.create().apply {
                        priority = LocationRequest.PRIORITY_HIGH_ACCURACY
                    },
                    locationCallback,
                    Looper.getMainLooper()
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        fusedLocationClient.removeLocationUpdates(locationCallback)

        val batteryLevel = getBatteryLevel()
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        val logMessage = "Fin de registro: $timestamp - Batería: $batteryLevel%\n"
        saveBatteryLog(logMessage)
        Log.i(TAG, "Servicio detenido")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Canal de Servicio de Ubicación",
                NotificationManager.IMPORTANCE_LOW
            )
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun getBatteryLevel(): Int {
        val batteryManager = getSystemService(BATTERY_SERVICE) as BatteryManager
        return batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }

    private fun saveBatteryLog(logMessage: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveLogWithMediaStore("bateria_log.txt", logMessage)
        } else {
            saveLogWithFileApi("bateria_log.txt", logMessage)
        }
    }

    private fun saveLogWithFileApi(fileName: String, logMessage: String) {
        try {
            val documentsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
            val appDir = File(documentsDir, "Creacion_de_Puntos")
            if (!appDir.exists()) appDir.mkdirs()

            val file = File(appDir, fileName)
            FileWriter(file, true).use { it.append(logMessage) }
        } catch (e: Exception) {
            Log.e(TAG, "Error al guardar log (FileAPI): ${e.message}", e)
        }
    }

    private fun saveLogWithMediaStore(fileName: String, logMessage: String) {
        val contentResolver = applicationContext.contentResolver
        val relativePath = Environment.DIRECTORY_DOCUMENTS + File.separator + "Creacion_de_Puntos"
        // FIX: The path for the selection query MUST end with a forward slash.
        val selectionPath = relativePath + File.separator

        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
        }
        val queryUri = MediaStore.Files.getContentUri("external")
        val projection = arrayOf(MediaStore.Files.FileColumns._ID)
        val selection = "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND ${MediaStore.MediaColumns.DISPLAY_NAME} = ?"
        // FIX: Use the selection path with the trailing slash for the query.
        val selectionArgs = arrayOf(selectionPath, fileName)

        var fileUri: Uri? = null
        contentResolver.query(queryUri, projection, selection, selectionArgs, null)?.use {
            if (it.moveToFirst()) {
                val id = it.getLong(it.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID))
                fileUri = ContentUris.withAppendedId(queryUri, id)
            }
        }
        if (fileUri == null) {
            fileUri = contentResolver.insert(queryUri, contentValues)
        }

        if (fileUri == null) {
            Log.e(TAG, "No se pudo crear/encontrar archivo de log.")
            return
        }

        try {
            contentResolver.openOutputStream(fileUri!!, "wa").use { it?.write(logMessage.toByteArray()) }
        } catch (e: Exception) {
            Log.e(TAG, "Error al guardar log (MediaStore): ${e.message}", e)
        }
    }

    private fun guardarUbicacion(location: Location) {
        val jsonObject = JSONObject().apply {
            put("lat", location.latitude)
            put("lon", location.longitude)
            put("timestamp", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()))
            put("source", "on-demand")
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            guardarConMediaStore(jsonObject)
        } else {
            guardarConFileApi(jsonObject)
        }
    }

    private fun guardarConFileApi(jsonObject: JSONObject) {
        try {
            val documentsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
            val appDir = File(documentsDir, "Creacion_de_Puntos")
            if (!appDir.exists()) appDir.mkdirs()

            val file = File(appDir, nombreArchivo)
            val existingArray = if (file.exists() && file.length() > 0) {
                try {
                    JSONArray(file.readText())
                } catch (e: Exception) { JSONArray() }
            } else { JSONArray() }

            existingArray.put(jsonObject)
            FileWriter(file).use { it.write(existingArray.toString(2)) }
        } catch (e: Exception) {
            Log.e(TAG, "Error (FileAPI): ${e.message}", e)
        }
    }

    private fun guardarConMediaStore(jsonObject: JSONObject) {
        val contentResolver = applicationContext.contentResolver
        val relativePath = Environment.DIRECTORY_DOCUMENTS + File.separator + "Creacion_de_Puntos"
        // FIX: The path for the selection query MUST end with a forward slash.
        val selectionPath = relativePath + File.separator

        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, nombreArchivo)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
        }

        val queryUri = MediaStore.Files.getContentUri("external")
        val projection = arrayOf(MediaStore.Files.FileColumns._ID)
        val selection = "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND ${MediaStore.MediaColumns.DISPLAY_NAME} = ?"
        // FIX: Use the selection path with the trailing slash for the query.
        val selectionArgs = arrayOf(selectionPath, nombreArchivo)

        var fileUri: Uri? = null
        contentResolver.query(queryUri, projection, selection, selectionArgs, null)?.use {
            if (it.moveToFirst()) {
                val id = it.getLong(it.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID))
                fileUri = ContentUris.withAppendedId(queryUri, id)
            }
        }

        if (fileUri == null) {
            fileUri = contentResolver.insert(queryUri, contentValues)
        }

        if (fileUri == null) {
            Log.e(TAG, "MediaStore: Falló el insert/find del archivo JSON")
            return
        }

        try {
            val existingArray = try {
                contentResolver.openInputStream(fileUri!!).use { it?.bufferedReader()?.readText()?.let { text ->
                    if (text.isNotEmpty()) JSONArray(text) else JSONArray()
                } ?: JSONArray() }
            } catch (e: Exception) { JSONArray() }

            existingArray.put(jsonObject)

            contentResolver.openOutputStream(fileUri!!, "w").use { it?.write(existingArray.toString(2).toByteArray()) }
            Log.i(TAG, "Ubicación guardada (MediaStore) en ${fileUri}, total entries: ${existingArray.length()}")
        } catch (e: Exception) {
            Log.e(TAG, "Error (MediaStore): ${e.message}", e)
        }
    }
}
