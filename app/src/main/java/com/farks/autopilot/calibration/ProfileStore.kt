package com.farks.autopilot.calibration

import android.content.Context
import java.io.File

/** Perfiles por juego en el almacenamiento privado de la app (un archivo por paquete). */
class ProfileStore(context: Context) {
    private val dir = File(context.filesDir, "profiles").apply { mkdirs() }

    private fun file(pkg: String) = File(dir, pkg.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".profile")

    fun save(p: StoredProfile) {
        val tmp = File(dir, "tmp.write")
        tmp.writeText(ProfileCodec.encode(p))
        if (!tmp.renameTo(file(p.profile.packageName))) {
            file(p.profile.packageName).writeText(tmp.readText()); tmp.delete()
        }
    }

    fun load(pkg: String): StoredProfile? {
        val f = file(pkg)
        return if (f.exists()) ProfileCodec.decode(f.readText()) else null
    }

    fun delete(pkg: String) { file(pkg).delete() }

    /** Captura de referencia usada en la calibración. */
    fun referenceFile(pkg: String) = File(dir, file(pkg).nameWithoutExtension + ".ref.png")
}
