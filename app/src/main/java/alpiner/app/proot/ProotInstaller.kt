package alpiner.app.proot

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Resolves the proot binary. With extractNativeLibs=true the APK always ships
 * its .so files extracted into nativeLibraryDir, which is also the only
 * app-owned location where exec() is allowed on modern Android (W^X):
 * anything copied to code_cache would be unexecutable anyway.
 */
object ProotInstaller {
    private const val TAG = "ProotInstaller"
    private const val PROOT_LIB = "libproot.so"

    fun getProotPath(context: Context): String? {
        val nativePath = "${context.applicationInfo.nativeLibraryDir}/$PROOT_LIB"
        val nativeFile = File(nativePath)
        if (nativeFile.canExecute()) return nativePath
        if (nativeFile.exists()) {
            // First run after an uninstall/reinstall may lose the exec bit.
            nativeFile.setExecutable(true, false)
            if (nativeFile.canExecute()) return nativePath
        }
        Log.e(TAG, "proot binary missing at $nativePath")
        return null
    }

    fun isInstalled(context: Context): Boolean = getProotPath(context) != null
}
