package tw.kevinzhang.moneylook.ui.home

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import tw.kevinzhang.moneylook.security.CreditCardPanAccessService
import javax.inject.Inject

/**
 * Intentionally stateless: a complete PAN must not survive in a ViewModel, SavedStateHandle,
 * StateFlow, or any other long-lived UI container.
 */
@HiltViewModel
class CardPanRevealViewModel @Inject constructor(
    private val panAccessService: CreditCardPanAccessService,
) : ViewModel() {
    suspend fun revealAfterUserAuthentication(cardInstrumentId: String): String? =
        panAccessService.revealAfterUserAuthentication(cardInstrumentId)
}
