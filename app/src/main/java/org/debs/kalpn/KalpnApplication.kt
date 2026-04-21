package org.debs.kalpn

import android.app.Application
import android.util.Log
import dagger.hilt.android.HiltAndroidApp
import go.Seq

@HiltAndroidApp
class KalpnApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        runCatching {
            Seq.setContext(applicationContext)
        }.onFailure {
            Log.e(TAG, "Runtime bootstrap failed.")
        }
    }

    private companion object {
        const val TAG = "AppInit"
    }
}
