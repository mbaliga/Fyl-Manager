package io.github.mbaliga.fylz

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.mbaliga.fylz.ui.FileHistoryOverlay
import io.github.mbaliga.fylz.ui.FylzAppShell

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize()) {
                    FylzAppShell()
                    FileHistoryOverlay(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(end = 20.dp, bottom = 152.dp),
                    )
                }
            }
        }
    }
}
