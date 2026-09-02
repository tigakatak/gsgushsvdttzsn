package com.redt.distro

data class Distro(
    val name: String,
    val displayName: String,
    val baseUrl: String,
    val sha256: String,
) {
    fun tarballUrl(): String = baseUrl
}
