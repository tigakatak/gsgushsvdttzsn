package alpiner.app.distro

data class Distro(
    val name: String,
    val displayName: String,
    val tarballUrl: String,
    val sha256: String,
)
