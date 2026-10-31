package com.karyatunggal.gesermap.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationManager
import android.location.provider.ProviderProperties
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.karyatunggal.gesermap.MainActivity
import com.karyatunggal.gesermap.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MockLocationService : Service() {

    private lateinit var locationManager: LocationManager
    private var serviceJob: Job? = null
    private val serviceScope = CoroutineScope(Dispatchers.Default)

    private var currentLat: Double = 0.0
    private var currentLng: Double = 0.0

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START

        when (action) {
            ACTION_START -> {
                val lat = intent?.getDoubleExtra(EXTRA_LATITUDE, 0.0) ?: 0.0
                val lng = intent?.getDoubleExtra(EXTRA_LONGITUDE, 0.0) ?: 0.0
                startMocking(lat, lng)
            }
            ACTION_UPDATE_LOCATION -> {
                val lat = intent?.getDoubleExtra(EXTRA_LATITUDE, currentLat) ?: currentLat
                val lng = intent?.getDoubleExtra(EXTRA_LONGITUDE, currentLng) ?: currentLng
                currentLat = lat
                currentLng = lng
                _currentMockLocation.value = Pair(lat, lng)
                updateNotification()
            }
            ACTION_STOP -> {
                stopMocking()
            }
        }

        return START_STICKY
    }

    private fun startMocking(lat: Double, lng: Double) {
        currentLat = lat
        currentLng = lng
        _currentMockLocation.value = Pair(lat, lng)

        try {
            setupTestProvider(LocationManager.GPS_PROVIDER)
            setupTestProvider(LocationManager.NETWORK_PROVIDER)
        } catch (_: SecurityException) {
            _serviceError.tryEmit("Izin Lokasi Palsu belum diaktifkan di Opsi Pengembang.")
            stopSelf()
            return
        } catch (e: Exception) {
            _serviceError.tryEmit("Gagal memulai penyedia lokasi palsu: ${e.localizedMessage}")
            stopSelf()
            return
        }

        val notification = createNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        _isRunning.value = true

        serviceJob?.cancel()
        serviceJob = serviceScope.launch {
            while (isActive) {
                pushMockLocation(LocationManager.GPS_PROVIDER, currentLat, currentLng)
                pushMockLocation(LocationManager.NETWORK_PROVIDER, currentLat, currentLng)
                delay(1000)
            }
        }
    }

    @SuppressLint("WrongConstant")
    private fun setupTestProvider(provider: String) {
        try {
            if (locationManager.getProvider(provider) != null) {
                try {
                    locationManager.removeTestProvider(provider)
                } catch (_: Exception) {}
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                locationManager.addTestProvider(
                    provider,
                    false, false, false, false, true, true, true,
                    ProviderProperties.POWER_USAGE_LOW,
                    ProviderProperties.ACCURACY_FINE
                )
            } else {
                @Suppress("DEPRECATION")
                locationManager.addTestProvider(
                    provider,
                    false, false, false, false, true, true, true,
                    1, 1
                )
            }
            locationManager.setTestProviderEnabled(provider, true)
        } catch (e: SecurityException) {
            throw e
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun pushMockLocation(provider: String, lat: Double, lng: Double) {
        val mockLocation = Location(provider).apply {
            latitude = lat
            longitude = lng
            altitude = 10.0
            time = System.currentTimeMillis()
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            accuracy = 1.0f
            speed = 0.0f
            bearing = 0.0f
        }
        try {
            locationManager.setTestProviderLocation(provider, mockLocation)
        } catch (_: SecurityException) {
            _serviceError.tryEmit("Izin lokasi palsu dicabut.")
            stopMocking()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun stopMocking() {
        serviceJob?.cancel()
        serviceJob = null

        removeTestProvider(LocationManager.GPS_PROVIDER)
        removeTestProvider(LocationManager.NETWORK_PROVIDER)

        _isRunning.value = false
        _currentMockLocation.value = null

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun removeTestProvider(provider: String) {
        try {
            locationManager.setTestProviderEnabled(provider, false)
            locationManager.removeTestProvider(provider)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "GeserMap Mock Location Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notifikasi aktif ketika aplikasi GeserMap mensimulasikan lokasi palsu."
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, MockLocationService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Lokasi Palsu Aktif")
            .setContentText("Lat: %.5f, Lng: %.5f".format(currentLat, currentLng))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .addAction(
                R.drawable.ic_launcher_foreground,
                "Hentikan",
                stopPendingIntent
            )
            .build()
    }

    private fun updateNotification() {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, createNotification())
    }

    override fun onDestroy() {
        stopMocking()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val CHANNEL_ID = "mock_location_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.karyatunggal.gesermap.ACTION_START"
        const val ACTION_UPDATE_LOCATION = "com.karyatunggal.gesermap.ACTION_UPDATE_LOCATION"
        const val ACTION_STOP = "com.karyatunggal.gesermap.ACTION_STOP"

        const val EXTRA_LATITUDE = "extra_latitude"
        const val EXTRA_LONGITUDE = "extra_longitude"

        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

        private val _currentMockLocation = MutableStateFlow<Pair<Double, Double>?>(null)
        val currentMockLocation: StateFlow<Pair<Double, Double>?> = _currentMockLocation.asStateFlow()

        private val _serviceError = MutableSharedFlow<String>(extraBufferCapacity = 1)
        val serviceError: SharedFlow<String> = _serviceError.asSharedFlow()

        fun startService(context: Context, latitude: Double, longitude: Double) {
            val intent = Intent(context, MockLocationService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_LATITUDE, latitude)
                putExtra(EXTRA_LONGITUDE, longitude)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun updateLocation(context: Context, latitude: Double, longitude: Double) {
            val intent = Intent(context, MockLocationService::class.java).apply {
                action = ACTION_UPDATE_LOCATION
                putExtra(EXTRA_LATITUDE, latitude)
                putExtra(EXTRA_LONGITUDE, longitude)
            }
            context.startService(intent)
        }

        fun stopService(context: Context) {
            val intent = Intent(context, MockLocationService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}
