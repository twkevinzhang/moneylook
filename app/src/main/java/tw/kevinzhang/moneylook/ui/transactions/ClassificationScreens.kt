package tw.kevinzhang.moneylook.ui.transactions

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.app.NotificationManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

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
            onCreateCategory = viewModel::createCategory,
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
    onNavigateToAiSettings: () -> Unit,
    viewModel: ClassificationViewModel = hiltViewModel(),
) {
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
        viewModel.refreshAiConfiguration()
    }
    val context = LocalContext.current
    var notificationsBlocked by rememberSaveable { mutableStateOf(false) }
    var notificationPermissionRequested by rememberSaveable { mutableStateOf(false) }
    fun classificationNotificationsEnabled(): Boolean {
        val manager = NotificationManagerCompat.from(context)
        return manager.areNotificationsEnabled() && (Build.VERSION.SDK_INT < 26 ||
            manager.getNotificationChannel("background_classification")?.importance != NotificationManager.IMPORTANCE_NONE)
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && classificationNotificationsEnabled()) viewModel.startApplyAllRules()
        else {
            viewModel.cancelApplyAllRules()
            notificationsBlocked = true
        }
    }
    if (notificationsBlocked) {
        AlertDialog(
            onDismissRequest = { notificationsBlocked = false },
            title = { Text("請允許背景分類通知") },
            text = { Text("背景分類需要通知顯示進度，並提供停止及保存功能。請在系統設定允許通知後，再回來開始分類。") },
            confirmButton = {
                TextButton(onClick = {
                    notificationsBlocked = false
                    context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                }) { Text("開啟通知設定") }
            },
            dismissButton = { TextButton(onClick = { notificationsBlocked = false }) { Text("取消") } },
        )
    }
    val overview = viewModel.autoClassificationOverview.collectAsStateWithLifecycle().value
    val scopeText = viewModel.applyScopeText.collectAsStateWithLifecycle().value
    val rules = viewModel.rules.collectAsStateWithLifecycle().value
    val categories = viewModel.categories.collectAsStateWithLifecycle().value
    val tags = viewModel.tags.collectAsStateWithLifecycle().value
    val accounts = viewModel.accounts.collectAsStateWithLifecycle().value
    val applyAllRulesUiState = viewModel.applyAllRulesUiState.collectAsStateWithLifecycle().value
    val classificationResetUiState = viewModel.classificationResetUiState.collectAsStateWithLifecycle().value
    AutoRuleListContent(
        overview = overview,
        onNavigateToAiSettings = onNavigateToAiSettings,
        rules = rules,
        categories = categories,
        tags = tags,
        accounts = accounts,
        onNavigateUp = onNavigateUp,
        onSave = viewModel::saveRule,
        onDelete = viewModel::deleteRule,
        applyAllRulesUiState = applyAllRulesUiState,
        onShowApplyAllRulesConfirmation = viewModel::showApplyAllRulesConfirmation,
        onCancelApplyAllRules = viewModel::cancelApplyAllRules,
        onStartApplyAllRules = {
            val needsPermission = Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            when {
                needsPermission && !notificationPermissionRequested -> {
                    notificationPermissionRequested = true
                    permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                needsPermission || !classificationNotificationsEnabled() -> {
                    viewModel.cancelApplyAllRules()
                    notificationsBlocked = true
                }
                else -> viewModel.startApplyAllRules()
            }
        },
        onRetryApplyAllRules = viewModel::retryApplyAllRules,
        onDismissApplyAllRules = viewModel::dismissApplyAllRules,
        classificationResetUiState = classificationResetUiState,
        onShowResetClassificationConfirmation = viewModel::showResetClassificationConfirmation,
        onCancelClassificationReset = viewModel::cancelClassificationReset,
        onStartClassificationReset = viewModel::startClassificationReset,
        onRetryClassificationReset = viewModel::retryClassificationReset,
        onDismissClassificationReset = viewModel::dismissClassificationReset,
        applyScopeText = scopeText,
        onStopApplyAllRules = viewModel::stopApplyAllRules,
    )
}
