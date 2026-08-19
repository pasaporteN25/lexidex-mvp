package com.lexidex.app

import android.app.Application
import com.lexidex.app.data.corpus.CorpusDatabaseProvider
import com.lexidex.app.data.knowledge.KnowledgeSource
import com.lexidex.app.data.knowledge.WikipediaKnowledgeSource
import com.lexidex.app.data.repository.CorpusRepository
import com.lexidex.app.data.userdb.UserDatabaseProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class LexidexApplication : Application() {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val corpusDatabaseProvider by lazy { CorpusDatabaseProvider(this, applicationScope) }
    private val userDatabaseProvider by lazy { UserDatabaseProvider(this, applicationScope) }

    val corpusRepository by lazy { CorpusRepository(corpusDatabaseProvider, userDatabaseProvider) }

    /**
     * The knowledge sources available when creating a term (ADR 0003). A list rather than a single
     * value because more sources are expected; the editor already treats it as one.
     */
    val knowledgeSources: List<KnowledgeSource> by lazy { listOf(WikipediaKnowledgeSource()) }
}
