package tw.kevinzhang.core.data.db

import androidx.room.TypeConverter
import tw.kevinzhang.core.data.model.AssetKind

class AssetKindConverters {
    @TypeConverter
    fun fromAssetKind(value: AssetKind): String = value.name

    @TypeConverter
    fun toAssetKind(value: String): AssetKind = AssetKind.valueOf(value)
}
