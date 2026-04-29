package org.debs.mayday

import android.app.Application
import android.util.Log
import dagger.hilt.android.HiltAndroidApp
import go.Seq

@HiltAndroidApp
class MaydayApplication : Application() {

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
