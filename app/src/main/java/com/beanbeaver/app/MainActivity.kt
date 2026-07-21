package com.beanbeaver.app

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import com.beanbeaver.app.receipt.BatchLaunchHolder
import com.beanbeaver.app.receipt.BatchRunner
import com.beanbeaver.app.ui.BeanBeaverApp
import com.beanbeaver.app.ui.theme.BeanBeaverTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Stash launch extras so BatchRunner can see autoRunBatch without
        // threading the Intent through Compose.
        (application as? BatchLaunchHolder)?.batchExtras = intent.extras
        val runBatch = intent?.getBooleanExtra(BatchRunner.EXTRA_AUTO_RUN_BATCH, false) == true

        enableEdgeToEdge()
        setContent {
            BeanBeaverTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    if (runBatch) {
                        var status by remember { mutableStateOf("Running on-device batch…") }
                        LaunchedEffect(Unit) {
                            try {
                                BatchRunner.runBatch(applicationContext)
                                status = "Batch done → ${BatchRunner.batchOutFile(applicationContext).absolutePath}"
                                Log.i("MainActivity", status)
                                // Stay alive so adb can pull batch_out.json; host kills us.
                            } catch (t: Throwable) {
                                status = "Batch failed: ${t.message}"
                                Log.e("MainActivity", "batch failed", t)
                            }
                        }
                        Text(status, modifier = Modifier.padding(24.dp))
                    } else {
                        BeanBeaverApp()
                    }
                }
            }
        }
    }
}
