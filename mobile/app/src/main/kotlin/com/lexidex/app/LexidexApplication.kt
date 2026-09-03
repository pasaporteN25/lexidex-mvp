package com.lexidex.app

import android.app.Application
import com.lexidex.app.data.corpus.CorpusDatabaseProvider
import com.lexidex.app.data.knowledge.KnowledgeSource
import com.lexidex.app.data.knowledge.KnowledgeSourceRegistry
import com.lexidex.app.data.knowledge.SourceSelectionStore
import com.lexidex.app.data.knowledge.WikipediaKnowledgeSource
import com.lexidex.app.data.repository.CorpusRepository
import com.lexidex.app.data.sync.KeystoreSyncBindingStore
import com.lexidex.app.data.sync.PreferencesSyncDeviceIdentity
import com.lexidex.app.data.sync.SyncRepository
import com.lexidex.app.data.userdb.UserDatabaseProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class LexidexApplication : Application() {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val corpusDatabaseProvider by lazy { CorpusDatabaseProvider(this, applicationScope) }
    private val userDatabaseProvider by lazy { UserDatabaseProvider(this, applicationScope) }

    private val deviceIdentity by lazy { PreferencesSyncDeviceIdentity(this) }

    val corpusRepository by lazy {
        CorpusRepository(corpusDatabaseProvider, userDatabaseProvider, deviceIdentity)
    }

    /**
     * Aparte de [corpusRepository] a proposito: la aplicacion funciona entera sin hub, y que la
     * consulta dependiera de algo que sabe de red seria mezclar dos cosas independientes.
     */
    val syncRepository by lazy {
        SyncRepository(
            userDatabaseProvider = userDatabaseProvider,
            corpusDatabaseProvider = corpusDatabaseProvider,
            bindingStore = KeystoreSyncBindingStore(this),
            deviceIdentity = deviceIdentity,
        )
    }

    /**
     * The knowledge sources available when creating a term (ADR 0003). A list rather than a single
     * value because more sources are expected; the editor already treats it as one.
     */
    private val knowledgeSourceRegistry by lazy {
        KnowledgeSourceRegistry(listOf(WikipediaKnowledgeSource()))
    }
    val knowledgeSources: List<KnowledgeSource> by lazy { knowledgeSourceRegistry.all }

    /** Que fuentes consulta el buscador; es una preferencia de este telefono (tarea 5.18). */
    val sourceSelectionStore by lazy { SourceSelectionStore(this) }
}
