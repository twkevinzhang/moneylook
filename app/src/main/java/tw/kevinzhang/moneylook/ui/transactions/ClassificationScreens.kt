package tw.kevinzhang.moneylook.ui.transactions

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun TransactionDetailScreen(
    onNavigateUp: () -> Unit,
    viewModel: ClassificationViewModel = hiltViewModel(),
) {
    val detail = viewModel.detail.collectAsStateWithLifecycle().value
    if (detail == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
    } else {
        TransactionDetailContent(
            state = detail,
            onNavigateUp = onNavigateUp,
            onSave = { draft -> viewModel.saveDetail(draft, onNavigateUp) },
        )
    }
}

@Composable
fun CategoryManagementScreen(
    onNavigateUp: () -> Unit,
    viewModel: ClassificationViewModel = hiltViewModel(),
) {
    val categories = viewModel.categories.collectAsStateWithLifecycle().value
    CategoryManagementContent(categories, onNavigateUp, viewModel::saveCategory, viewModel::deleteCategory)
}

@Composable
fun TagManagementScreen(
    onNavigateUp: () -> Unit,
    viewModel: ClassificationViewModel = hiltViewModel(),
) {
    val tags = viewModel.tags.collectAsStateWithLifecycle().value
    TagManagementContent(tags, onNavigateUp, viewModel::saveTag, viewModel::deleteTag)
}

@Composable
fun AutoRuleScreen(
    onNavigateUp: () -> Unit,
    viewModel: ClassificationViewModel = hiltViewModel(),
) {
    val rules = viewModel.rules.collectAsStateWithLifecycle().value
    val categories = viewModel.categories.collectAsStateWithLifecycle().value
    val tags = viewModel.tags.collectAsStateWithLifecycle().value
    val accounts = viewModel.accounts.collectAsStateWithLifecycle().value
    AutoRuleListContent(
        rules = rules,
        categories = categories,
        tags = tags,
        accounts = accounts,
        onNavigateUp = onNavigateUp,
        onSave = viewModel::saveRule,
        onDelete = viewModel::deleteRule,
        onApplyExisting = viewModel::applyRuleToExisting,
    )
}
