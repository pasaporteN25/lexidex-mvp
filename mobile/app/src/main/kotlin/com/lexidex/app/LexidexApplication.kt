package com.lexidex.app

import android.app.Application
import com.lexidex.app.data.corpus.CorpusDatabaseProvider
import com.lexidex.app.data.repository.CorpusRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class LexidexApplication : Application() {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val corpusDatabaseProvider by lazy { CorpusDatabaseProvider(this, applicationScope) }

    val corpusRepository by lazy { CorpusRepository(corpusDatabaseProvider) }
}
