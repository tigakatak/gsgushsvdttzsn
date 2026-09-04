package alpiner.app.util

object Format {
    fun size(sizeBytes: Long): String = when {
        sizeBytes < 1_000_000 -> "${(sizeBytes + 999) / 1000} KB"
        sizeBytes < 1_000_000_000 -> "${"%.1f".format(sizeBytes / 1_000_000.0)} MB"
        else -> "${"%.2f".format(sizeBytes / 1_000_000_000.0)} GB"
    }
}
