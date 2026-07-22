package tw.kevinzhang.moneylook.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    bottomBar: @Composable () -> Unit = {},
    onNavigateToCategories: () -> Unit = {},
    onNavigateToTags: () -> Unit = {},
    onNavigateToRules: () -> Unit = {},
) {
    Scaffold(
        topBar = {
            TopAppBar(title = { Text("設定") })
        },
        bottomBar = bottomBar,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Top,
        ) {
            SettingsRow("分類管理", "建立、改名或刪除交易分類", onNavigateToCategories)
            HorizontalDivider()
            SettingsRow("標籤管理", "建立可套用至多筆交易的標籤", onNavigateToTags)
            HorizontalDivider()
            SettingsRow("自動分類規則", "依交易描述、收支、金額及帳戶自動套用", onNavigateToRules)
        }
    }
}

@Composable
private fun SettingsRow(title: String, description: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(title)
        Text(description, color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
