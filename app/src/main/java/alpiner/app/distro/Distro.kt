package alpiner.app.distro

import android.content.Context
import java.io.File

data class Distro(
    val name: String,
    val displayName: String,
    val tarballUrl: String,
    val sha256: String,
)

/** Single source of the rootfs parent directory used by installer, sweeper and SAF provider. */
object DistroPaths {
    fun rootfsParent(context: Context): File = File(context.filesDir, "rootfs")
}
