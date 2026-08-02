package io.github.mbaliga.fylz

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import io.github.mbaliga.fylz.ui.FylzApp
import io.github.mbaliga.fylz.ui.FylzTheme
import io.github.mbaliga.fylz.model.ThemeMode

class MainActivity : ComponentActivity() {
    private val viewModel: BrowserViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val folderPicker = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.OpenDocumentTree(),
            ) { uri ->
                if (uri != null) {
                    val readGrant = Intent.FLAG_GRANT_READ_URI_PERMISSION
                    val readWriteGrant = readGrant or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    val persisted = runCatching {
                        contentResolver.takePersistableUriPermission(uri, readWriteGrant)
                    }.recoverCatching {
                        // Some read-only providers reject a write grant even though the
                        // selected tree remains useful. Persist the narrower grant instead.
                        contentResolver.takePersistableUriPermission(uri, readGrant)
                    }.isSuccess

                    if (!persisted) {
                        Toast.makeText(
                            this,
                            "This provider may need to be selected again after Fylz restarts.",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                    viewModel.openRoot(uri)
                }
            }

            FylzApp(
                viewModel = viewModel,
                onPickFolder = { folderPicker.launch(null) },
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun ThemePreview() {
    FylzTheme(ThemeMode.DARK) {}
}
