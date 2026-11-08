package com.farks.autopilot.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.farks.autopilot.automation.GestureAccessibilityService
import com.farks.autopilot.calibration.ProfileStore
import com.farks.autopilot.diagnostics.AutopilotState
import com.farks.autopilot.diagnostics.Snapshot
import com.farks.autopilot.service.AutopilotService
import com.farks.autopilot.settings.AppSettings

class MainActivity : AppCompatActivity() {

    private lateinit var settings: AppSettings
    private lateinit var store: ProfileStore
    private lateinit var gameButton: TextView
    private lateinit var permsBox: android.widget.LinearLayout
    private lateinit var statusText: TextView
    private lateinit var startStop: android.widget.Button

    private val listener: (Snapshot) -> Unit = { runOnUiThread { renderStatus(it) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = AppSettings(this)
        store = ProfileStore(this)

        val col = Ui.column(this)
        col.addView(Ui.title(this, "FarKs AutoPilot"))
        col.addView(Ui.text(this, "Piloto automático local para juegos de 3 carriles. Todo ocurre en el teléfono: sin red ni claves.", 13f, Ui.MUTED))

        val gameCard = Ui.card(this)
        gameCard.addView(Ui.text(this, "Juego seleccionado", 12f, Ui.MUTED))
        gameButton = Ui.text(this, "", 17f)
        gameCard.addView(gameButton)
        gameCard.addView(Ui.button(this, "Elegir juego") { chooseGame() })
        col.addView(gameCard)

        val permCard = Ui.card(this)
        permCard.addView(Ui.text(this, "Permisos y calibración", 12f, Ui.MUTED))
        permsBox = Ui.column(this).apply { setPadding(0, 0, 0, 0) }
        permCard.addView(permsBox)
        col.addView(permCard)

        val runCard = Ui.card(this)
        statusText = Ui.text(this, "", 14f)
        runCard.addView(statusText)
        startStop = Ui.button(this, "INICIAR", Ui.GREEN) { onStartStop() }
        runCard.addView(startStop)
        col.addView(runCard)

        col.addView(Ui.button(this, "Calibrar el juego") { calibrate() })
        col.addView(Ui.button(this, "Diagnóstico") { startActivity(Intent(this, DiagnosticsActivity::class.java)) })
        col.addView(Ui.button(this, "Ajustes") { startActivity(Intent(this, SettingsActivity::class.java)) })

        col.addView(Ui.spacer(this, 16))
        col.addView(Ui.text(this,
            "Este es un piloto basado en visión por computadora, no un truco infalible. Úselo solo en juegos y modos donde " +
                "la automatización esté permitida (por ejemplo, un juego sin competencia en línea). Cada juego debe calibrarse.",
            12f, Ui.MUTED))

        setContentView(Ui.scroll(this, col))
    }

    override fun onResume() {
        super.onResume()
        AutopilotState.addListener(listener)
        refresh()
        renderStatus(AutopilotState.snapshot)
    }

    override fun onPause() {
        AutopilotState.removeListener(listener)
        super.onPause()
    }

    private fun label(pkg: String?): String {
        if (pkg == null) return "(ninguno)"
        return try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            "$pkg (no instalado)"
        }
    }

    private fun refresh() {
        val pkg = settings.selectedPackage
        gameButton.text = label(pkg)
        permsBox.removeAllViews()

        fun item(ok: Boolean, text: String, fixLabel: String?, fix: (() -> Unit)?) {
            val r = Ui.row(this)
            r.addView(Ui.text(this, (if (ok) "✔ " else "✖ ") + text, 14f, if (ok) Ui.GREEN else Ui.RED).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            if (!ok && fixLabel != null && fix != null) {
                r.addView(android.widget.Button(this).apply { this.text = fixLabel; isAllCaps = false; setOnClickListener { fix() } })
            }
            permsBox.addView(r)
        }

        item(Settings.canDrawOverlays(this), "Superposición sobre otras apps", "Conceder") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        item(GestureAccessibilityService.isConnected, "Servicio de accesibilidad (gestos)", "Abrir") { openAccessibility() }
        val notifOk = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        item(notifOk, "Notificaciones", "Conceder") { requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1) }
        val calibrated = pkg != null && store.load(pkg) != null
        item(calibrated, if (calibrated) "Juego calibrado" else "Juego sin calibrar", null, null)
        permsBox.addView(Ui.text(this, "La captura de pantalla se pide cada vez que pulsa INICIAR (Android 14+ solo concede un uso).", 12f, Ui.MUTED))
    }

    private fun renderStatus(s: Snapshot) {
        val o = s.output
        statusText.text = when {
            s.running && o != null -> "En marcha · ${o.status} · carril ${o.playerLane} · ${"%.0f".format(s.analysisFps)} análisis/s"
            s.running -> "Iniciando…"
            s.lastError != null -> "Detenido: ${s.lastError}"
            else -> "Detenido"
        }
        startStop.text = if (s.running) "DETENER" else "INICIAR"
    }

    private fun chooseGame() {
        val pm = packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(intent, 0)
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .filter { it.first != packageName }
            .distinctBy { it.first }
            .sortedBy { it.second.lowercase() }
        if (apps.isEmpty()) {
            Toast.makeText(this, "No se encontraron aplicaciones.", Toast.LENGTH_LONG).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Elegir juego")
            .setItems(apps.map { "${it.second}\n${it.first}" }.toTypedArray()) { _, i ->
                settings.selectedPackage = apps[i].first
                refresh()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun openAccessibility() {
        AlertDialog.Builder(this)
            .setTitle("Activar el servicio de gestos")
            .setMessage(
                "1. Se abrirá Accesibilidad. Busque «FarKs AutoPilot (gestos)» y actívelo.\n\n" +
                    "Si el interruptor está gris («ajuste restringido»), porque instaló el APK fuera de Play Store: " +
                    "vaya a Ajustes → Aplicaciones → FarKs AutoPilot → menú ⋮ → «Permitir ajustes restringidos» y repita.\n\n" +
                    "El servicio solo desliza el dedo dentro del juego elegido y no lee el contenido de la pantalla.",
            )
            .setPositiveButton("Abrir accesibilidad") { _, _ -> startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            .setNeutralButton("Info de la app") { _, _ ->
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun calibrate() {
        val pkg = settings.selectedPackage ?: return toast("Elija primero un juego.")
        if (!Settings.canDrawOverlays(this)) return toast("Conceda el permiso de superposición.")
        startActivity(
            Intent(this, ProjectionPermissionActivity::class.java)
                .putExtra(AutopilotService.EXTRA_PACKAGE, pkg)
                .putExtra(AutopilotService.EXTRA_MODE, AutopilotService.MODE_CALIBRATION),
        )
    }

    private fun onStartStop() {
        if (AutopilotState.snapshot.running) {
            AutopilotService.stop(this)
            return
        }
        val pkg = settings.selectedPackage ?: return toast("Elija primero un juego.")
        if (store.load(pkg) == null) return toast("Calibre el juego antes de iniciar.")
        if (settings.showOverlay && !Settings.canDrawOverlays(this)) return toast("Conceda el permiso de superposición o desactívela en Ajustes.")
        if (!GestureAccessibilityService.isConnected) {
            toast("Accesibilidad inactiva: se analizará, pero NO se enviarán gestos.")
        }
        startActivity(
            Intent(this, ProjectionPermissionActivity::class.java)
                .putExtra(AutopilotService.EXTRA_PACKAGE, pkg)
                .putExtra(AutopilotService.EXTRA_MODE, AutopilotService.MODE_AUTOPILOT),
        )
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_LONG).show()
}
