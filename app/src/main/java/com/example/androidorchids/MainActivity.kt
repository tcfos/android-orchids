package com.example.androidorchids

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.Location
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import android.widget.Button
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
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
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Polyline
import com.google.android.gms.maps.model.PolylineOptions
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

class MainActivity : AppCompatActivity(), OnMapReadyCallback {
    private var mMap: GoogleMap? = null
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var lastKnownLocation: Location? = null
    private var isRecording = false
    private val pathPoints = mutableListOf<LatLng>()
    private lateinit var recordButton: Button
    private lateinit var exportButton: Button
    private lateinit var locationRequest: LocationRequest
    private var locationCallback: LocationCallback? = null
    private var polyline: Polyline? = null

    private val requestPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { isGranted: Boolean ->
            if (isGranted) {
                getDeviceLocation()
            }
        }

    private val createDocumentLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let { exportTrackToFile(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        val mapFragment = supportFragmentManager
            .findFragmentById(R.id.map) as? SupportMapFragment
        mapFragment?.getMapAsync(this)

        recordButton = findViewById(R.id.record_button)
        recordButton.setOnClickListener {
            if (isRecording) {
                stopRecording()
            } else {
                startRecording()
            }
        }

        exportButton = findViewById(R.id.export_button)
        exportButton.setOnClickListener {
            if (pathPoints.isNotEmpty()) {
                createDocumentLauncher.launch("hiking_track.json")
            } else {
                Toast.makeText(this, "No track to export", Toast.LENGTH_SHORT).show()
            }
        }

        val locationRequestBuilder = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 10000)
        locationRequestBuilder.setMinUpdateIntervalMillis(5000)
        locationRequest = locationRequestBuilder.build()


        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                super.onLocationResult(locationResult)
                if (isRecording) {
                    locationResult.lastLocation?.let { location ->
                        val latLng = LatLng(location.latitude, location.longitude)
                        pathPoints.add(latLng)
                        updatePolyline()
                    }
                }
            }
        }
    }

    override fun onMapReady(googleMap: GoogleMap) {
        mMap = googleMap
        polyline = mMap?.addPolyline(PolylineOptions().width(10f).color(Color.BLUE))
        updateLocationUI()
        getDeviceLocation()
    }

    private fun startRecording() {
        isRecording = true
        recordButton.text = "Stop Recording"
        pathPoints.clear()
        polyline?.points = pathPoints
        startLocationUpdates()
    }

    private fun stopRecording() {
        isRecording = false
        recordButton.text = "Start Recording"
        stopLocationUpdates()
    }

    private fun startLocationUpdates() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            locationCallback?.let {
                fusedLocationClient.requestLocationUpdates(locationRequest, it, Looper.getMainLooper())
            }
        }
    }

    private fun stopLocationUpdates() {
        locationCallback?.let {
            fusedLocationClient.removeLocationUpdates(it)
        }
    }

    private fun updatePolyline() {
        polyline?.points = pathPoints
    }

    private fun exportTrackToFile(uri: Uri) {
        val json = buildJsonTrack()
        try {
            contentResolver.openOutputStream(uri)?.use {
                it.write(json.toByteArray())
            }
            Toast.makeText(this, "Track exported successfully", Toast.LENGTH_SHORT).show()
            showJsonContent(json)
        } catch (e: IOException) {
            Toast.makeText(this, "Failed to export track", Toast.LENGTH_SHORT).show()
            e.printStackTrace()
        }
    }

    private fun showJsonContent(json: String) {
        AlertDialog.Builder(this)
            .setTitle("Exported Track")
            .setMessage(json)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun buildJsonTrack(): String {
        val root = JSONObject()
        val track = JSONArray()
        pathPoints.forEach { latLng ->
            val point = JSONObject()
            point.put("latitude", latLng.latitude)
            point.put("longitude", latLng.longitude)
            track.put(point)
        }
        root.put("track", track)
        return root.toString(4)
    }

    private fun updateLocationUI() {
        if (mMap == null) {
            return
        }
        try {
            if (ContextCompat.checkSelfPermission(this.applicationContext, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) {
                mMap?.isMyLocationEnabled = true
                mMap?.uiSettings?.isMyLocationButtonEnabled = true
            } else {
                mMap?.isMyLocationEnabled = false
                mMap?.uiSettings?.isMyLocationButtonEnabled = false
                lastKnownLocation = null
                requestLocationPermission()
            }
        } catch (e: SecurityException) {
            e.printStackTrace()
        }
    }

    private fun requestLocationPermission() {
        requestPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    private fun getDeviceLocation() {
        try {
            if (ContextCompat.checkSelfPermission(this.applicationContext, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) {
                val locationResult = fusedLocationClient.lastLocation
                locationResult.addOnCompleteListener(this) { task ->
                    if (task.isSuccessful) {
                        lastKnownLocation = task.result
                        if (lastKnownLocation != null) {
                            val currentLatLng = LatLng(lastKnownLocation!!.latitude,
                                lastKnownLocation!!.longitude)
                            mMap?.moveCamera(CameraUpdateFactory.newLatLngZoom(
                                currentLatLng, 15f))
                        }
                    } else {
                        mMap?.uiSettings?.isMyLocationButtonEnabled = false
                    }
                }
            }
        } catch (e: SecurityException) {
            e.printStackTrace()
        }
    }
}
