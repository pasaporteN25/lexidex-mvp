package com.lexidex.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.lexidex.app.ui.AppReadiness
import com.lexidex.app.ui.AppReadinessViewModel
import com.lexidex.app.ui.LexidexApp

class MainActivity : ComponentActivity() {

    /**
     * El mismo `AppReadinessViewModel` que despues usa `LexidexApp`.
     *
     * `viewModel()` dentro de `setContent` resuelve contra el `ViewModelStore` de esta Activity y
     * con la misma clave derivada del tipo, asi que las dos referencias son la misma instancia: la
     * splash y la interfaz miran un unico estado y no pueden desincronizarse.
     */
    private val readiness: AppReadinessViewModel by viewModels {
        AppReadinessViewModel.factory((application as LexidexApplication).corpusRepository)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Antes de `super.onCreate` porque es lo que engancha la splash del sistema con la
        // ventana de la aplicacion; despues ya no hay nada que enganchar.
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)

        // Se sostiene mientras dura la preparacion local imprescindible -verificar el checksum del
        // paquete y abrirlo- y nada mas: sin `delay`, sin minimo de tiempo y sin esperar la red.
        // `Error` tambien la suelta, porque un error recuperable hay que poder leerlo.
        splash.setKeepOnScreenCondition { readiness.state.value is AppReadiness.Loading }

        enableEdgeToEdge()
        val application = application as LexidexApplication
        setContent {
            LexidexApp(
                repository = application.corpusRepository,
                knowledgeSources = application.knowledgeSources,
                syncRepository = application.syncRepository,
                sourceSelectionStore = application.sourceSelectionStore,
            )
        }
    }
}
