package com.lexidex.app

import android.app.Application
import com.lexidex.app.data.corpus.CorpusDatabaseProvider
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
}
