package tw.kevinzhang.moneylook.ai

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import tw.kevinzhang.core.data.model.AiCategoryProposal
import javax.inject.Inject

fun interface AiClassifier {
    suspend fun classify(input: AiClassificationInput): AiCategoryProposal
}
class CloudAiClassifier @Inject constructor(private val store: AiSettingsStore, private val client: AiClassificationClient) : AiClassifier, AiBatchClassifier {
    override suspend fun classify(input: AiClassificationInput): AiCategoryProposal {
        val settings = (store.load() as? AiSettingsLoadResult.Ready)?.settings
            ?: throw AiClassificationException(AiFailureCode.SETTINGS)
        return client.classify(settings, input)
    }
    override suspend fun classifyBatch(items: List<AiBatchItem>): AiBatchResult {
        val settings = (store.load() as? AiSettingsLoadResult.Ready)?.settings
            ?: throw AiClassificationException(AiFailureCode.SETTINGS)
        return client.classifyBatch(settings, items)
    }
}
@Module
@InstallIn(SingletonComponent::class)
abstract class AiModule {
    @Binds abstract fun classifier(impl: CloudAiClassifier): AiClassifier
    @Binds abstract fun batchClassifier(impl: CloudAiClassifier): AiBatchClassifier
}
