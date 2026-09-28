package alpiner.app.distro

import android.content.Context
import java.io.File

/** The one guest distribution Alpiner ships, and where it lives on disk. */
object Alpine {
    const val NAME = "alpine"
    const val DISPLAY_NAME = "Alpine Linux 3.24.1"
    const val TARBALL_URL =
        "https://dl-cdn.alpinelinux.org/alpine/v3.24/releases/aarch64/alpine-minirootfs-3.24.1-aarch64.tar.gz"
    const val SHA256 = "f55a90f69052c5bd6f92cb09a8f47065970830b194c917a006fb94028e721259"

    /** Parent of the rootfs directory; the orphan sweep scans it. */
    fun rootfsParent(context: Context): File = File(context.filesDir, "rootfs")

    /**
     * The Alpine rootfs (not canonicalized, as the SAF provider exposes it).
     * Never pre-created: its absence means "not installed".
     */
    fun rootfsDir(context: Context): File = File(rootfsParent(context), NAME)
}
