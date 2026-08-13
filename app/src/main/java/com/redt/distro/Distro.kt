package com.redt.distro

data class Distro(
    val name: String,
    val displayName: String,
    val description: String,
    val baseUrl: String,
    val sha256: String,
    val packageManager: String,
    val urlArch: String = "aarch64"
) {
    fun tarballUrl(): String = baseUrl.replace("{arch}", urlArch)
}
