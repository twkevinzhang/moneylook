package tw.kevinzhang.marketplace.data

data class ExtensionIndexEntry(
    val id: String,
    val name: String,
    val version: Int,
    val versionName: String,
    val path: String,   // subdirectory in the repo, e.g. "tw.bot"
)
