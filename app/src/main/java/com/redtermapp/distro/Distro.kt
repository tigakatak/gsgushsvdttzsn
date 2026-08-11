package com.redtermapp.distro

data class Distro(
    val name: String,
    val displayName: String,
    val description: String,
    val baseUrl: String,
    val sha256: Map<String, String>,
    val prootArchs: List<String>,
    val packageManager: String,
    val archOverride: Map<String, String> = emptyMap()
) {
    fun tarballUrlFor(deviceArch: String): String {
        val arch = abiToProotArch(deviceArch)
        val urlArch = archOverride[arch] ?: arch
        return baseUrl.replace("{arch}", urlArch)
    }

    fun sha256For(deviceArch: String): String {
        val arch = abiToProotArch(deviceArch)
        return sha256[arch] ?: ""
    }
}

fun abiToProotArch(abi: String): String = "aarch64"
