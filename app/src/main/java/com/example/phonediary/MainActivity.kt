package com.example.phonediary

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import com.example.phonediary.ui.DiaryScreen
import com.example.phonediary.worker.DiaryGenerationWorker

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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
