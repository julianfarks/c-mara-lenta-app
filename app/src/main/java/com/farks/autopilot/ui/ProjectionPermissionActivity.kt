package com.farks.autopilot.ui

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.farks.autopilot.service.AutopilotService

/**
 * Pide el permiso de captura de pantalla (un solo uso en Android 14+) y arranca el servicio con el
 * resultado. Actividad transparente: el usuario solo ve el diálogo del sistema.
 */
class ProjectionPermissionActivity : AppCompatActivity() {

    private val launcher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val pkg = intent.getStringExtra(AutopilotService.EXTRA_PACKAGE)
        if (r.resultCode == Activity.RESULT_OK && r.data != null && pkg != null) {
            val svc = Intent(this, AutopilotService::class.java)
                .setAction(AutopilotService.ACTION_START)
                .putExtra(AutopilotService.EXTRA_RESULT_CODE, r.resultCode)
                .putExtra(AutopilotService.EXTRA_DATA, r.data)
                .putExtra(AutopilotService.EXTRA_PACKAGE, pkg)
                .putExtra(AutopilotService.EXTRA_MODE, intent.getStringExtra(AutopilotService.EXTRA_MODE))
            startForegroundService(svc)
            packageManager.getLaunchIntentForPackage(pkg)?.let {
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(it)
            }
        } else {
            Toast.makeText(this, "Sin permiso de captura no se puede analizar el juego.", Toast.LENGTH_LONG).show()
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            launcher.launch(mpm.createScreenCaptureIntent())
        }
    }
}
