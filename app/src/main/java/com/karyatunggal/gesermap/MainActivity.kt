package com.karyatunggal.gesermap

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.karyatunggal.gesermap.service.MockLocationService
import com.karyatunggal.gesermap.utils.FavoriteLocation
import com.karyatunggal.gesermap.utils.FavoritesManager
import com.karyatunggal.gesermap.utils.MockLocationHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class MainActivity : AppCompatActivity(), SensorEventListener, OnMapReadyCallback {

    private lateinit var googleMap: GoogleMap
    private lateinit var etSearch: EditText
    private lateinit var btnSearch: ImageButton
    private lateinit var btnFavorites: ImageButton
    private lateinit var pbSearch: ProgressBar
    private lateinit var cardDevOptionsWarning: MaterialCardView
    private lateinit var btnOpenDevOptions: MaterialButton
    private lateinit var btnAdbGuide: MaterialButton
    private lateinit var btnCompass: ImageButton
    private lateinit var btnMyLocation: ImageButton
    private lateinit var tvStatusBadge: TextView
    private lateinit var tvCoordinates: TextView
    private lateinit var tvAddress: TextView
    private lateinit var btnSaveFavorite: MaterialButton
    private lateinit var btnToggleMock: MaterialButton
    private lateinit var adView: AdView
    private var mInterstitialAd: InterstitialAd? = null

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private var magnetometer: Sensor? = null

    private val gravity = FloatArray(3)
    private val geomagnetic = FloatArray(3)
    private var hasGravity = false
    private var hasGeomagnetic = false

    private var selectedLatLng = LatLng(-6.175392, 106.827153) // Monas, Jakarta until real GPS fetched
    private var realGpsLatLng: LatLng? = null

    private var mockLocationMarker: Marker? = null
    private var realLocationMarker: Marker? = null
    private var currentHeading: Float = 0.0f

    private var reverseGeocodeJob: Job? = null
    private var isMockingActive = false

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val lastLoc = result.lastLocation ?: return
            val gpsLatLng = LatLng(lastLoc.latitude, lastLoc.longitude)
            realGpsLatLng = gpsLatLng

            if (!isMockingActive) {
                updateRealLocationMarker(gpsLatLng, currentHeading)
            }
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        val coarseGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] ?: false
        if (fineGranted || coarseGranted) {
            startLocationUpdates()
            fetchRealLocationAndCenterMap()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

        // Initialize Google Mobile Ads SDK
        MobileAds.initialize(this) {}

        setContentView(R.layout.activity_main)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        initViews()
        setupMapFragment()
        setupListeners()
        observeMockServiceState()
        requestLocationPermissions()

        loadBannerAd()
        loadInterstitialAd()
        fetchRealLocationAndCenterMap()
    }

    override fun onResume() {
        super.onResume()
        adView.resume()
        checkDeveloperOptionsStatus()
        startLocationUpdates()
        fetchRealLocationAndCenterMap()

        accelerometer?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        magnetometer?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
    }

    override fun onPause() {
        super.onPause()
        adView.pause()
        stopLocationUpdates()
        sensorManager.unregisterListener(this)
    }

    override fun onDestroy() {
        adView.destroy()
        super.onDestroy()
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                System.arraycopy(event.values, 0, gravity, 0, event.values.size)
                hasGravity = true
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                System.arraycopy(event.values, 0, geomagnetic, 0, event.values.size)
                hasGeomagnetic = true
            }
        }
        if (hasGravity && hasGeomagnetic) {
            val r = FloatArray(9)
            val i = FloatArray(9)
            if (SensorManager.getRotationMatrix(r, i, gravity, geomagnetic)) {
                val orientation = FloatArray(3)
                SensorManager.getOrientation(r, orientation)
                val azimuthRad = orientation[0]
                var azimuthDeg = Math.toDegrees(azimuthRad.toDouble()).toFloat()
                azimuthDeg = (azimuthDeg + 360) % 360
                azimuthDeg = (360 - azimuthDeg) % 360

                currentHeading = azimuthDeg
                val currentPoint = realGpsLatLng ?: selectedLatLng
                if (!isMockingActive) {
                    updateRealLocationMarker(currentPoint, currentHeading)
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun initViews() {
        etSearch = findViewById(R.id.etSearch)
        btnSearch = findViewById(R.id.btnSearch)
        btnFavorites = findViewById(R.id.btnFavorites)
        pbSearch = findViewById(R.id.pbSearch)
        cardDevOptionsWarning = findViewById(R.id.cardDevOptionsWarning)
        btnOpenDevOptions = findViewById(R.id.btnOpenDevOptions)
        btnAdbGuide = findViewById(R.id.btnAdbGuide)
        btnCompass = findViewById(R.id.btnCompass)
        btnMyLocation = findViewById(R.id.btnMyLocation)
        tvStatusBadge = findViewById(R.id.tvStatusBadge)
        tvCoordinates = findViewById(R.id.tvCoordinates)
        tvAddress = findViewById(R.id.tvAddress)
        btnSaveFavorite = findViewById(R.id.btnSaveFavorite)
        btnToggleMock = findViewById(R.id.btnToggleMock)
        adView = findViewById(R.id.adView)
    }

    private fun loadBannerAd() {
        val adRequest = AdRequest.Builder().build()
        adView.loadAd(adRequest)
    }

    private fun setupMapFragment() {
        val mapFragment = supportFragmentManager.findFragmentById(R.id.mapView) as? SupportMapFragment
        mapFragment?.getMapAsync(this)
    }

    override fun onMapReady(map: GoogleMap) {
        googleMap = map
        googleMap.uiSettings.isZoomControlsEnabled = false
        googleMap.uiSettings.isCompassEnabled = false
        googleMap.uiSettings.isRotateGesturesEnabled = true
        googleMap.uiSettings.isTiltGesturesEnabled = true

        googleMap.moveCamera(CameraUpdateFactory.newLatLngZoom(selectedLatLng, 15f))

        setupMapListeners()

        updateCoordinatesDisplay(selectedLatLng.latitude, selectedLatLng.longitude)
        fetchAddressForLocation(selectedLatLng.latitude, selectedLatLng.longitude)
        updateMockLocationMarker(selectedLatLng)
    }

    private fun setupMapListeners() {
        googleMap.setOnMapLongClickListener { latLng ->
            setMockLocationPoint(latLng)
            Toast.makeText(
                this,
                "Titik lokasi palsu ditentukan!",
                Toast.LENGTH_SHORT
            ).show()
        }

        googleMap.setOnCameraMoveListener {
            btnCompass.rotation = googleMap.cameraPosition.bearing
        }
    }

    private fun bitmapDescriptorFromVector(context: Context, vectorResId: Int): com.google.android.gms.maps.model.BitmapDescriptor? {
        val drawable = ContextCompat.getDrawable(context, vectorResId) ?: return null
        val width = if (drawable.intrinsicWidth > 0) drawable.intrinsicWidth else 96
        val height = if (drawable.intrinsicHeight > 0) drawable.intrinsicHeight else 96
        drawable.setBounds(0, 0, width, height)
        val bm = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bm)
        drawable.draw(canvas)
        return com.google.android.gms.maps.model.BitmapDescriptorFactory.fromBitmap(bm)
    }

    private fun updateRealLocationMarker(latLng: LatLng, rotation: Float) {
        if (!::googleMap.isInitialized) return
        if (realLocationMarker == null) {
            val iconDescriptor = bitmapDescriptorFromVector(this, R.drawable.ic_location_with_beam)
            val options = MarkerOptions()
                .position(latLng)
                .title("Lokasi Saya (Asli)")
                .anchor(0.5f, 0.5f)
            iconDescriptor?.let { options.icon(it) }
            realLocationMarker = googleMap.addMarker(options)
        } else {
            realLocationMarker?.position = latLng
        }
        realLocationMarker?.rotation = rotation
    }

    private fun setMockLocationPoint(latLng: LatLng) {
        selectedLatLng = latLng
        updateMockLocationMarker(latLng)

        updateCoordinatesDisplay(latLng.latitude, latLng.longitude)
        fetchAddressForLocation(latLng.latitude, latLng.longitude)

        if (isMockingActive) {
            MockLocationService.updateLocation(this, latLng.latitude, latLng.longitude)
            updateRealLocationMarker(latLng, currentHeading)
        }
    }

    private fun updateMockLocationMarker(latLng: LatLng) {
        if (!::googleMap.isInitialized) return
        if (mockLocationMarker == null) {
            val iconDescriptor = bitmapDescriptorFromVector(this, R.drawable.ic_location_pin)
            val options = MarkerOptions()
                .position(latLng)
                .title("Lokasi Palsu Terpilih")
                .anchor(0.5f, 1.0f)
            iconDescriptor?.let { options.icon(it) }
            mockLocationMarker = googleMap.addMarker(options)
        } else {
            mockLocationMarker?.position = latLng
        }
    }

    private fun setupListeners() {
        btnSearch.setOnClickListener {
            performLocationSearch()
        }

        btnFavorites.setOnClickListener {
            showFavoritesDialog()
        }

        btnSaveFavorite.setOnClickListener {
            showSaveFavoriteDialog()
        }

        etSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                performLocationSearch()
                true
            } else {
                false
            }
        }

        btnOpenDevOptions.setOnClickListener {
            MockLocationHelper.openDeveloperSettings(this)
        }

        btnAdbGuide.setOnClickListener {
            showAdbGuideDialog()
        }

        btnCompass.setOnClickListener {
            if (::googleMap.isInitialized) {
                val currentCamera = googleMap.cameraPosition
                val target = CameraPosition.Builder(currentCamera)
                    .bearing(0f)
                    .tilt(0f)
                    .build()
                googleMap.animateCamera(CameraUpdateFactory.newCameraPosition(target))
                btnCompass.rotation = 0f
            }
        }

        btnMyLocation.setOnClickListener {
            moveToMyLocation()
        }

        btnToggleMock.setOnClickListener {
            if (isMockingActive) {
                MockLocationService.stopService(this)
            } else {
                if (MockLocationHelper.isDeviceRooted()) {
                    MockLocationHelper.grantMockLocationViaRoot(this)
                }

                if (!MockLocationHelper.isMockLocationEnabled(this)) {
                    cardDevOptionsWarning.visibility = View.VISIBLE
                    Toast.makeText(
                        this,
                        "Aktifkan 'GeserMap' sebagai aplikasi lokasi palsu di Opsi Pengembang terlebih dahulu.",
                        Toast.LENGTH_LONG
                    ).show()
                    MockLocationHelper.openDeveloperSettings(this)
                } else {
                    cardDevOptionsWarning.visibility = View.GONE
                    showInterstitialAdBeforeMock()
                }
            }
        }
    }

    private fun loadInterstitialAd() {
        val adRequest = AdRequest.Builder().build()
        InterstitialAd.load(
            this,
            "ca-app-pub-3940256099942544/1033173712", // Test Interstitial Ad Unit ID
            adRequest,
            object : InterstitialAdLoadCallback() {
                override fun onAdLoaded(interstitialAd: InterstitialAd) {
                    mInterstitialAd = interstitialAd
                }

                override fun onAdFailedToLoad(loadAdError: LoadAdError) {
                    mInterstitialAd = null
                }
            }
        )
    }

    private fun showInterstitialAdBeforeMock() {
        if (mInterstitialAd != null) {
            mInterstitialAd?.fullScreenContentCallback = object : FullScreenContentCallback() {
                override fun onAdDismissedFullScreenContent() {
                    mInterstitialAd = null
                    loadInterstitialAd()
                    startMockLocationAction()
                }

                override fun onAdFailedToShowFullScreenContent(adError: AdError) {
                    mInterstitialAd = null
                    loadInterstitialAd()
                    startMockLocationAction()
                }
            }
            mInterstitialAd?.show(this)
        } else {
            startMockLocationAction()
            loadInterstitialAd()
        }
    }

    private fun startMockLocationAction() {
        if (MockLocationHelper.isDeviceRooted()) {
            Toast.makeText(
                this,
                "Akses Root terdeteksi: Bypass lokasi palsu aktif otomatis!",
                Toast.LENGTH_SHORT
            ).show()
        }
        MockLocationService.startService(
            this,
            selectedLatLng.latitude,
            selectedLatLng.longitude
        )
    }

    private fun showSaveFavoriteDialog() {
        val context = this
        val builder = AlertDialog.Builder(context)
        builder.setTitle("Simpan ke Lokasi Favorit")

        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 16)
        }

        val inputName = EditText(context).apply {
            hint = "Nama Lokasi"
            setHintTextColor(Color.parseColor("#BF757575")) // 75% opacity grey
        }

        val infoText = TextView(context).apply {
            text = "Koordinat: Lat %.6f, Lng %.6f\nAlamat: %s".format(
                Locale.US,
                selectedLatLng.latitude,
                selectedLatLng.longitude,
                tvAddress.text.toString()
            )
            setPadding(0, 16, 0, 0)
            textSize = 13f
        }

        layout.addView(inputName)
        layout.addView(infoText)
        builder.setView(layout)

        builder.setPositiveButton("Simpan") { _, _ ->
            val name = inputName.text.toString().trim()
            if (name.isEmpty()) {
                Toast.makeText(context, "Nama lokasi tidak boleh kosong", Toast.LENGTH_SHORT).show()
                return@setPositiveButton
            }

            val favorite = FavoriteLocation(
                name = name,
                latitude = selectedLatLng.latitude,
                longitude = selectedLatLng.longitude,
                address = tvAddress.text.toString()
            )

            FavoritesManager.saveFavorite(context, favorite)
            Toast.makeText(context, "Berhasil menyimpan '$name' ke Favorit!", Toast.LENGTH_SHORT).show()
        }

        builder.setNegativeButton("Batal", null)
        builder.show()
    }

    private fun showFavoritesDialog() {
        val context = this
        val favorites = FavoritesManager.getFavorites(context)

        if (favorites.isEmpty()) {
            Toast.makeText(context, "Belum ada lokasi favorit yang tersimpan.", Toast.LENGTH_SHORT).show()
            return
        }

        val items = favorites.map { "${it.name}\nLat: %.5f, Lng: %.5f".format(Locale.US, it.latitude, it.longitude) }.toTypedArray()

        AlertDialog.Builder(context)
            .setTitle("❤️ Lokasi Favorit Saya")
            .setItems(items) { _, which ->
                val fav = favorites[which]
                val latLng = LatLng(fav.latitude, fav.longitude)
                selectedLatLng = latLng
                updateMockLocationMarker(latLng)
                if (::googleMap.isInitialized) {
                    googleMap.animateCamera(CameraUpdateFactory.newLatLngZoom(latLng, 16f))
                }
                updateCoordinatesDisplay(latLng.latitude, latLng.longitude)
                fetchAddressForLocation(latLng.latitude, latLng.longitude)

                if (isMockingActive) {
                    MockLocationService.updateLocation(this, latLng.latitude, latLng.longitude)
                    updateRealLocationMarker(latLng, currentHeading)
                }

                Toast.makeText(context, "Memuat lokasi: ${fav.name}", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("Hapus...") { _, _ ->
                showDeleteFavoriteDialog(favorites)
            }
            .setNegativeButton("Tutup", null)
            .show()
    }

    private fun showDeleteFavoriteDialog(favorites: List<FavoriteLocation>) {
        val context = this
        val items = favorites.map { it.name }.toTypedArray()
        val checkedItems = BooleanArray(favorites.size) { false }

        AlertDialog.Builder(context)
            .setTitle("Pilih Favorit yang Ingin Dihapus")
            .setMultiChoiceItems(items, checkedItems) { _, which, isChecked ->
                checkedItems[which] = isChecked
            }
            .setPositiveButton("Hapus") { _, _ ->
                for (i in favorites.indices) {
                    if (checkedItems[i]) {
                        FavoritesManager.removeFavorite(context, favorites[i].id)
                    }
                }
                Toast.makeText(context, "Favorit yang dipilih telah dihapus", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun observeMockServiceState() {
        lifecycleScope.launch {
            MockLocationService.isRunning.collectLatest { running ->
                isMockingActive = running
                updateUiForMockStatus(running)
            }
        }

        lifecycleScope.launch {
            MockLocationService.serviceError.collectLatest { errorMessage ->
                Toast.makeText(this@MainActivity, errorMessage, Toast.LENGTH_LONG).show()
                checkDeveloperOptionsStatus()
            }
        }
    }

    private fun updateUiForMockStatus(isRunning: Boolean) {
        if (isRunning) {
            tvStatusBadge.text = "Aktif"
            tvStatusBadge.setBackgroundColor(Color.parseColor("#4CAF50")) // Green
            tvStatusBadge.setTextColor(Color.WHITE)

            btnToggleMock.text = "Hentikan Lokasi Palsu"
            btnToggleMock.setBackgroundColor(Color.parseColor("#D32F2F")) // Red

            // Move real location marker to fake location point when mock is running
            updateRealLocationMarker(selectedLatLng, currentHeading)
        } else {
            tvStatusBadge.text = "Nonaktif"
            tvStatusBadge.setBackgroundColor(Color.parseColor("#E0E0E0")) // Grey
            tvStatusBadge.setTextColor(Color.parseColor("#616161"))

            btnToggleMock.text = "Mulai Lokasi Palsu"
            btnToggleMock.setBackgroundColor(Color.parseColor("#1976D2")) // Primary Blue

            // Move real location marker back to actual GPS location
            if (realGpsLatLng != null) {
                updateRealLocationMarker(realGpsLatLng!!, currentHeading)
            } else {
                fetchRealLocationAndCenterMap()
            }
        }
    }

    private fun checkDeveloperOptionsStatus() {
        val isEnabled = MockLocationHelper.isMockLocationEnabled(this)
        cardDevOptionsWarning.visibility = if (isEnabled) View.GONE else View.VISIBLE
    }

    private fun updateCoordinatesDisplay(lat: Double, lng: Double) {
        tvCoordinates.text = "Lat: %.6f, Lng: %.6f".format(Locale.US, lat, lng)
    }

    private fun fetchAddressForLocation(lat: Double, lng: Double) {
        reverseGeocodeJob?.cancel()
        reverseGeocodeJob = lifecycleScope.launch {
            delay(500) // Debounce
            val addressText = withContext(Dispatchers.IO) {
                try {
                    val geocoder = Geocoder(this@MainActivity, Locale("id", "ID"))
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        var result: String? = null
                        geocoder.getFromLocation(lat, lng, 1) { addresses ->
                            result = formatAddress(addresses.firstOrNull())
                        }
                        delay(300)
                        result
                    } else {
                        @Suppress("DEPRECATION")
                        val addresses = geocoder.getFromLocation(lat, lng, 1)
                        formatAddress(addresses?.firstOrNull())
                    }
                } catch (e: Exception) {
                    null
                }
            }

            tvAddress.text = addressText ?: "Lokasi terpilih (%.5f, %.5f)".format(Locale.US, lat, lng)
        }
    }

    private fun formatAddress(address: Address?): String? {
        if (address == null) return null
        val parts = mutableListOf<String>()
        for (i in 0..address.maxAddressLineIndex) {
            address.getAddressLine(i)?.let { parts.add(it) }
        }
        return if (parts.isNotEmpty()) parts.joinToString(", ") else address.featureName
    }

    private fun performLocationSearch() {
        val query = etSearch.text.toString().trim()
        if (query.isEmpty()) {
            Toast.makeText(this, "Masukkan nama lokasi yang ingin dicari", Toast.LENGTH_SHORT).show()
            return
        }

        hideKeyboard()
        pbSearch.visibility = View.VISIBLE
        btnSearch.visibility = View.INVISIBLE

        lifecycleScope.launch(Dispatchers.IO) {
            val targetLatLng = try {
                val geocoder = Geocoder(this@MainActivity, Locale("id", "ID"))
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    var result: LatLng? = null
                    geocoder.getFromLocationName(query, 1) { addresses ->
                        addresses.firstOrNull()?.let {
                            result = LatLng(it.latitude, it.longitude)
                        }
                    }
                    delay(500)
                    result
                } else {
                    @Suppress("DEPRECATION")
                    val addresses = geocoder.getFromLocationName(query, 1)
                    addresses?.firstOrNull()?.let {
                        LatLng(it.latitude, it.longitude)
                    }
                }
            } catch (e: Exception) {
                null
            }

            withContext(Dispatchers.Main) {
                pbSearch.visibility = View.GONE
                btnSearch.visibility = View.VISIBLE

                if (targetLatLng != null) {
                    setMockLocationPoint(targetLatLng)
                    if (::googleMap.isInitialized) {
                        googleMap.animateCamera(CameraUpdateFactory.newLatLngZoom(targetLatLng, 16f))
                    }
                } else {
                    Toast.makeText(this@MainActivity, "Lokasi '$query' tidak ditemukan", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        val fineGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarseGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (!fineGranted && !coarseGranted) return

        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 3000)
            .setMinUpdateIntervalMillis(1000)
            .build()

        fusedLocationClient.requestLocationUpdates(
            locationRequest,
            locationCallback,
            Looper.getMainLooper()
        )
    }

    private fun stopLocationUpdates() {
        fusedLocationClient.removeLocationUpdates(locationCallback)
    }

    @SuppressLint("MissingPermission")
    private fun fetchRealLocationAndCenterMap() {
        val fineGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarseGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (!fineGranted && !coarseGranted) return

        fusedLocationClient.lastLocation.addOnSuccessListener { location: Location? ->
            if (location != null) {
                val gpsLatLng = LatLng(location.latitude, location.longitude)
                realGpsLatLng = gpsLatLng

                if (!isMockingActive) {
                    updateRealLocationMarker(gpsLatLng, currentHeading)
                    if (::googleMap.isInitialized) {
                        googleMap.animateCamera(CameraUpdateFactory.newLatLngZoom(gpsLatLng, 16f))
                    }
                    updateCoordinatesDisplay(gpsLatLng.latitude, gpsLatLng.longitude)
                    fetchAddressForLocation(gpsLatLng.latitude, gpsLatLng.longitude)
                } else {
                    updateRealLocationMarker(gpsLatLng, currentHeading)
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun moveToMyLocation() {
        if (realGpsLatLng != null && !isMockingActive) {
            if (::googleMap.isInitialized) {
                googleMap.animateCamera(CameraUpdateFactory.newLatLngZoom(realGpsLatLng!!, 16f))
            }
            updateCoordinatesDisplay(realGpsLatLng!!.latitude, realGpsLatLng!!.longitude)
            fetchAddressForLocation(realGpsLatLng!!.latitude, realGpsLatLng!!.longitude)
        } else {
            fetchRealLocationAndCenterMap()
        }
    }

    private fun requestLocationPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val allGranted = permissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

        if (!allGranted) {
            permissionLauncher.launch(permissions.toTypedArray())
        }
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        val view = currentFocus ?: View(this)
        imm.hideSoftInputFromWindow(view.windowToken, 0)
    }

    private fun showAdbGuideDialog() {
        val context = this
        val builder = AlertDialog.Builder(context)
        builder.setTitle("Panduan Non-Root (ADB)")
        builder.setMessage(
            "Bagi pengguna ponsel Non-Root, Anda dapat memberikan izin lokasi palsu secara instan melalui ADB (tanpa harus masuk ke Opsi Pengembang):\n\n" +
            "1. Hubungkan ponsel ke PC atau aktifkan Wireless Debugging di Opsi Pengembang.\n" +
            "2. Jalankan perintah ADB berikut di terminal/CMD:\n\n" +
            "adb shell appops set com.karyatunggal.gesermap MOCK_LOCATION allow\n\n" +
            "Setelah dijalankan, aplikasi akan langsung bisa digunakan!"
        )
        builder.setPositiveButton("Salin Perintah") { _, _ ->
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clip = android.content.ClipData.newPlainText("ADB Command", "adb shell appops set com.karyatunggal.gesermap MOCK_LOCATION allow")
            clipboard.setPrimaryClip(clip)
            Toast.makeText(context, "Perintah ADB disalin ke Clipboard!", Toast.LENGTH_SHORT).show()
        }
        builder.setNegativeButton("Tutup", null)
        builder.show()
    }
}
