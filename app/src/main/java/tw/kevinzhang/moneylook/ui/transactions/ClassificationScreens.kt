package tw.kevinzhang.moneylook.ui.transactions

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.collect

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
            onLoadSourceBody = viewModel::loadSourceDocumentBody,
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
    val isApplyingAllRules = viewModel.applyingAllRules.collectAsStateWithLifecycle().value
    val isResettingClassification = viewModel.resettingClassification.collectAsStateWithLifecycle().value
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(viewModel) {
        viewModel.autoRuleApplicationMessages.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }
    AutoRuleListContent(
        rules = rules,
        categories = categories,
        tags = tags,
        accounts = accounts,
        onNavigateUp = onNavigateUp,
        onSave = viewModel::saveRule,
        onDelete = viewModel::deleteRule,
        isApplyingAllRules = isApplyingAllRules,
        onApplyAllRules = viewModel::applyAllRulesToExistingTransactions,
        isResettingClassification = isResettingClassification,
        onResetClassificationSystem = viewModel::resetClassificationSystem,
        snackbarHostState = snackbarHostState,
    )
}
