package com.shubham0204.ml.ocmsclient

import android.Manifest
import android.app.ActivityManager
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.*
import android.provider.Settings
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.*
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.shubham0204.ml.ocmsclient.databinding.ActivityMainBinding
import java.util.concurrent.Executors


class MainActivity : AppCompatActivity() {

    private lateinit var viewBinding : ActivityMainBinding
    private lateinit var onScreenAppListener : OnScreenAppListener
    private lateinit var onScreenStatusListener: OnScreenStatusListener
    private val userID = "shubham_panchal"
    private lateinit var frameAnalyzer: FrameAnalyzer
    private lateinit var firebaseDBManager: FirebaseDBManager
    private lateinit var foregroundAppServiceIntent : Intent
    private lateinit var sharedPreferences: SharedPreferences


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewBinding = ActivityMainBinding.inflate( layoutInflater )
        setContentView( viewBinding.root )

        onScreenAppListener = OnScreenAppListener( this )



        if ( checkCameraPermission() ) {
            startCameraPreview()
        }
        else {
            requestCameraPermission()
        }


        onScreenAppListener.getForegroundApp()
        firebaseDBManager = FirebaseDBManager( userID )
        frameAnalyzer = FrameAnalyzer( firebaseDBManager )
        sharedPreferences = getSharedPreferences( getString( R.string.app_name ) , Context.MODE_PRIVATE )
        onScreenStatusListener = OnScreenStatusListener( lifecycle , activityLifecycleCallback )

        if ( checkUsageStatsPermission() ) {
            // Implement further app logic here ...
        }
        else {
            Intent( Settings.ACTION_USAGE_ACCESS_SETTINGS ).apply {
                startActivity( this )
            }
        }




    }


    private val activityLifecycleCallback = object : OnScreenStatusListener.Callback {

        override fun inForeground(secondsSinceBackground: Int?) {
            firebaseDBManager.updateOnScreenStatus( true )
            notifyCameraAudioPermissionStatus()
            if ( sharedPreferences.getBoolean( getString( R.string.service_running_status_key ) , false ) ) {
                stopService( Intent( this@MainActivity , ForegroundAppService::class.java) )
            }
        }

        override fun inBackground() {
            firebaseDBManager.updateOnScreenStatus( false )
            foregroundAppServiceIntent = Intent( this@MainActivity , ForegroundAppService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService( foregroundAppServiceIntent )
            }
            else {
                startService( foregroundAppServiceIntent )
            }
        }

    }



    // The `PACKAGE_USAGE_STATS` permission is a not a runtime permission and hence cannot be
    // requested directly using `ActivityCompat.requestPermissions`. All special permissions
    // are handled by `AppOpsManager`.
    private fun checkUsageStatsPermission() : Boolean {
        val appOpsManager = getSystemService(APP_OPS_SERVICE) as AppOpsManager
        // `AppOpsManager.checkOpNoThrow` is deprecated from Android Q
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOpsManager.unsafeCheckOpNoThrow(
                "android:get_usage_stats",
                Process.myUid(), packageName
            )
        }
        else {
            appOpsManager.checkOpNoThrow(
                "android:get_usage_stats",
                Process.myUid(), packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun notifyCameraAudioPermissionStatus() {
        firebaseDBManager.updateCameraPermissionStatus( checkUsageStatsPermission() )
        firebaseDBManager.updateAudioPermissionStatus( checkAudioPermission() )
    }


    private fun requestCameraPermission() {
        cameraPermissionRequestLauncher.launch( Manifest.permission.CAMERA )
    }

    private val cameraPermissionRequestLauncher = registerForActivityResult( ActivityResultContracts.RequestPermission() ) {
            isGranted ->
        if ( isGranted ) {
            startCameraPreview()
        }
        else {
            val alertDialog = MaterialAlertDialogBuilder( this ).apply {
                setTitle( "Camera Permission")
                setMessage( "The app couldn't function without the camera permission." )
                setCancelable( false )
                setPositiveButton( "ALLOW" ) { dialog, which ->
                    dialog.dismiss()
                    requestCameraPermission()
                }
                setNegativeButton( "CLOSE" ) { dialog, which ->
                    dialog.dismiss()
                    finish()
                }
                create()
            }
            alertDialog.show()
        }
    }

    private fun checkCameraPermission() : Boolean = checkSelfPermission( Manifest.permission.CAMERA ) ==
                PackageManager.PERMISSION_GRANTED

    private fun checkAudioPermission() : Boolean = ActivityCompat.checkSelfPermission( this , Manifest.permission.RECORD_AUDIO ) ==
                PackageManager.PERMISSION_GRANTED

    private fun startCameraPreview() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance( this )
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview : Preview = Preview.Builder().build()
            val cameraSelector : CameraSelector = CameraSelector.Builder()
                .requireLensFacing( CameraSelector.LENS_FACING_FRONT )
                .build()
            preview.setSurfaceProvider( viewBinding.cameraPreviewview.surfaceProvider )
            val imageFrameAnalysis = ImageAnalysis.Builder()
                .setTargetAspectRatio( AspectRatio.RATIO_4_3 )
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            imageFrameAnalysis.setAnalyzer(Executors.newSingleThreadExecutor(), frameAnalyzer )
            cameraProvider.bindToLifecycle(this as LifecycleOwner, cameraSelector, preview , imageFrameAnalysis )
        }, ContextCompat.getMainExecutor(this) )
    }

}

