package tw.kevinzhang.moneylook.ui.home

import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference

private const val PAN_REVEAL_DURATION_MS = 30_000L
private const val PAN_AUTHENTICATORS =
    BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL

/**
 * The complete PAN never enters Compose state. After authentication it lives only in this native
 * TextView, is inaccessible to accessibility semantics, and is cleared on timeout/background/
 * disposal. The native view is also intentionally non-selectable, so it cannot be copied.
 */
@Composable
internal fun SecurePanReveal(
    cardInstrumentId: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    viewModel: CardPanRevealViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val activity = context.findFragmentActivity()
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val panView = remember(cardInstrumentId) { AtomicReference<PanTextView?>(null) }

    DisposableEffect(lifecycleOwner, panView) {
        val observer = object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) {
                panView.get()?.clearPan()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            panView.getAndSet(null)?.clearPan()
        }
    }

    TextButton(
        modifier = modifier,
        enabled = enabled && activity != null,
        onClick = {
            activity?.authenticatePan(
                onAuthenticated = {
                    scope.launch {
                        // The storage boundary is reached only after the BiometricPrompt callback.
                        val pan = viewModel.revealAfterUserAuthentication(cardInstrumentId)
                        if (pan == null) {
                            Toast.makeText(context, "無法顯示完整卡號", Toast.LENGTH_SHORT).show()
                        } else if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                            panView.get()?.showPan(pan)
                        }
                    }
                },
                onError = { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() },
            )
        },
    ) { Text("顯示完整卡號") }
    AndroidView(
        factory = { viewContext ->
            PanTextView(viewContext, activity).also(panView::set)
        },
        modifier = modifier,
    )
}

internal class PanTextView(
    context: android.content.Context,
    private val activity: FragmentActivity?,
) : TextView(context) {
    private var secured = false
    private val clearPanRunnable = Runnable { clearPan() }

    init {
        visibility = View.GONE
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        isFocusable = false
        setTextIsSelectable(false)
    }

    fun showPan(value: String) {
        clearPan()
        activity?.window?.let { window ->
            SecureWindowFlag.acquire(window)
            secured = true
        }
        text = value
        visibility = View.VISIBLE
        postDelayed(clearPanRunnable, PAN_REVEAL_DURATION_MS)
    }

    fun clearPan() {
        removeCallbacks(clearPanRunnable)
        text = ""
        visibility = View.GONE
        if (secured) {
            activity?.window?.let(SecureWindowFlag::release)
            secured = false
        }
    }

    override fun onDetachedFromWindow() {
        clearPan()
        super.onDetachedFromWindow()
    }
}

private fun FragmentActivity.authenticatePan(
    onAuthenticated: () -> Unit,
    onError: (String) -> Unit,
) {
    if (BiometricManager.from(this).canAuthenticate(PAN_AUTHENTICATORS) != BiometricManager.BIOMETRIC_SUCCESS) {
        onError("此裝置無法使用生物辨識或螢幕鎖定驗證")
        return
    }
    val prompt = BiometricPrompt(
        this,
        ContextCompat.getMainExecutor(this),
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                onAuthenticated()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                onError("驗證未完成")
            }
        },
    )
    prompt.authenticate(
        BiometricPrompt.PromptInfo.Builder()
            .setTitle("顯示完整卡號")
            .setSubtitle("請驗證身分後查看 30 秒")
            .setAllowedAuthenticators(PAN_AUTHENTICATORS)
            .build(),
    )
}

private tailrec fun android.content.Context.findFragmentActivity(): FragmentActivity? = when (this) {
    is FragmentActivity -> this
    is android.content.ContextWrapper -> baseContext.findFragmentActivity()
    else -> null
}

/** Multiple card rows can overlap briefly; only clear FLAG_SECURE after the final reveal hides. */
private object SecureWindowFlag {
    private val reveals = java.util.IdentityHashMap<Window, Int>()

    fun acquire(window: Window) = synchronized(reveals) {
        val count = (reveals[window] ?: 0) + 1
        reveals[window] = count
        if (count == 1) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    fun release(window: Window) = synchronized(reveals) {
        val remaining = (reveals[window] ?: 1) - 1
        if (remaining <= 0) {
            reveals.remove(window)
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            reveals[window] = remaining
        }
    }
}
