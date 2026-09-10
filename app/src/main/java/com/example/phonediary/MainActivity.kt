package com.example.phonediary

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import com.example.phonediary.ui.DiaryScreen
import com.example.phonediary.worker.DiaryGenerationWorker

class MainActivity : ComponentActivity() {

    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* results ignored here; UI re-checks permission state on its own */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestPermissions.launch(
            arrayOf(
                Manifest.permission.READ_CALENDAR,
                Manifest.permission.WRITE_CALENDAR,
                Manifest.permission.RECORD_AUDIO
            )
        )

        DiaryGenerationWorker.schedule(applicationContext)

        setContent {
            MaterialTheme {
                Surface {
                    DiaryScreen()
                }
            }
        }
    }
}
