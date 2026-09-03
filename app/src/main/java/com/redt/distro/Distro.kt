package com.redt.distro

data class Distro(
    val name: String,
    val displayName: String,
    val tarballUrl: String,
    val sha256: String,
)
