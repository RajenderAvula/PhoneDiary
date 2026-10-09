package com.example.phonediary

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.example.phonediary.reminders.GeofenceHelper
import com.example.phonediary.ui.AppTheme
import com.example.phonediary.ui.DiaryScreen
import com.example.phonediary.ui.PhoneDiaryTheme
import com.example.phonediary.ui.ThemePrefs
import com.example.phonediary.worker.DiaryGenerationWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* results ignored here; UI re-checks permission state on its own */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashLogger.install(this)

        // Geofences don't survive reboot, force-stop, or a Play Services reset,
        // so re-register every enabled location reminder on each app start.
        CoroutineScope(Dispatchers.IO).launch {
            GeofenceHelper.resyncAllEntries(applicationContext)
        }

        val permissions = mutableListOf(
            Manifest.permission.READ_CALENDAR,
            Manifest.permission.WRITE_CALENDAR,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        requestPermissions.launch(permissions.toTypedArray())

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
