package tw.kevinzhang.marketplace.data

data class ExtensionManifest(
    val id: String,
    val name: String,
    val version: Int,
    val versionName: String,
    val description: String,
    val loginUrl: String,
    val targetDomains: List<String>,
    val scriptPath: String,
    val iconUrl: String?,
)
