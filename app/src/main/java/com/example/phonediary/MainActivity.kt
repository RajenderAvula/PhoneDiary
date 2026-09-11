package com.example.phonediary

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.example.phonediary.ui.AppTheme
import com.example.phonediary.ui.DiaryScreen
import com.example.phonediary.ui.PhoneDiaryTheme
import com.example.phonediary.ui.ThemePrefs
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
            var currentTheme by remember { mutableStateOf(ThemePrefs.getTheme(applicationContext)) }

            PhoneDiaryTheme(theme = currentTheme) {
                Surface {
                    DiaryScreen(
                        currentTheme = currentTheme,
                        onThemeChange = { newTheme ->
                            currentTheme = newTheme
                            ThemePrefs.setTheme(applicationContext, newTheme)
                        }
                    )
                }
            }
        }
    }
}
