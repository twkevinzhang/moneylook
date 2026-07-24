package tw.kevinzhang.moneylook.security

import kotlinx.coroutines.CancellationException
import tw.kevinzhang.core.data.db.CreditCardInstrumentDao
import javax.inject.Inject
import javax.inject.Singleton

/**
 * PAN access boundary for the authenticated UI flow. It deliberately does not expose card rows
 * or decrypted values as Flow state; callers must authenticate and keep the returned value brief.
 */
@Singleton
class CreditCardPanAccessService @Inject constructor(
    private val cardDao: CreditCardInstrumentDao,
    private val protector: CardPanProtector,
) {
    suspend fun revealAfterUserAuthentication(cardInstrumentId: String): String? {
        val card = cardDao.getById(cardInstrumentId) ?: return null
        val ciphertext = card.panCiphertext ?: return null
        val iv = card.panIv ?: return null
        return try {
            protector.reveal(ciphertext, iv)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }
}
