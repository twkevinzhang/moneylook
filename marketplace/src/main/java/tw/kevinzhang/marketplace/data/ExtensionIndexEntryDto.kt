package tw.kevinzhang.marketplace.data

import com.google.gson.annotations.SerializedName

data class ExtensionIndexEntryDto(
    @SerializedName("id") val id: String = "",
    @SerializedName("name") val name: String = "",
    @SerializedName("version") val version: Int = 1,
    @SerializedName("versionName") val versionName: String = "1.0.0",
    @SerializedName("path") val path: String = "",
) {
    fun toDomain() = ExtensionIndexEntry(id, name, version, versionName, path)
}
