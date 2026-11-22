package io.kanzo.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.activity.enableEdgeToEdge
import io.kanzo.mobile.theme.KanzoTheme
import io.kanzo.mobile.ui.KanzoApp
import io.kanzo.mobile.ui.KanzoViewModel

class MainActivity : ComponentActivity() {
    private val viewModel: KanzoViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            KanzoTheme {
                KanzoApp(viewModel = viewModel)
            }
        }
    }
}
