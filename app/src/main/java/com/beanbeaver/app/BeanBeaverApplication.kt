package com.beanbeaver.app

import android.app.Application
import android.os.Bundle
import com.beanbeaver.app.receipt.BatchLaunchHolder
import com.beanbeaver.app.receipt.ModelStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class BeanBeaverApplication : Application(), BatchLaunchHolder {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override var batchExtras: Bundle? = null

    override fun onCreate() {
        super.onCreate()
        // Copy ONNX models out of assets onto a real filesystem path the Rust
        // ORT loader can open (assets are not plain files).
        appScope.launch {
            runCatching { ModelStore.ensureModels(this@BeanBeaverApplication) }
        }
    }
}
