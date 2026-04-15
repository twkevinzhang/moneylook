package tw.kevinzhang.moneylook.ui.marketplace

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManageReposScreen(
    onNavigateUp: () -> Unit,
    viewModel: ManageReposViewModel = hiltViewModel(),
) {
    val repoUrls by viewModel.repoUrls.collectAsStateWithLifecycle()
    val addRepoUrl by viewModel.addRepoUrl.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("管理 Repos") },
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
        ) {
            item {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("新增 Extension 來源", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(
                        value = addRepoUrl,
                        onValueChange = {
                            viewModel.onAddRepoUrlChanged(it)
                            if (error != null) viewModel.clearError()
                        },
                        label = { Text("GitHub repo URL") },
                        placeholder = { Text("https://github.com/owner/moneylook-extensions") },
                        singleLine = true,
                        isError = error != null,
                        supportingText = error?.let {
                            { Text(it, color = MaterialTheme.colorScheme.error) }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        onClick = viewModel::addRepo,
                        enabled = addRepoUrl.isNotBlank() && !isLoading,
                        modifier = Modifier.align(Alignment.End),
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Text("新增")
                        }
                    }
                }
                HorizontalDivider()
            }

            if (repoUrls.isNotEmpty()) {
                item {
                    Text(
                        text = "已新增的 Repos",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
                    )
                }
                items(repoUrls.toList(), key = { it }) { url ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = url.repoDisplayName(),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { viewModel.removeRepo(url) }) {
                            Icon(Icons.Filled.Delete, contentDescription = "刪除")
                        }
                    }
                    HorizontalDivider(modifier = Modifier.padding(start = 16.dp))
                }
            }
        }
    }
}

internal fun String.repoDisplayName(): String =
    removePrefix("https://github.com/").trimEnd('/')
