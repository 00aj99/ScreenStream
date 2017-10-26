package info.dvkr.screenstream

import android.app.Application
import android.os.StrictMode
import android.util.Log
import com.squareup.leakcanary.LeakCanary
import info.dvkr.screenstream.dagger.component.AppComponent
import info.dvkr.screenstream.dagger.component.DaggerAppComponent
import info.dvkr.screenstream.dagger.module.AppModule


class ScreenStreamApp : Application() {
    private val TAG = "ScreenStreamApp"
    private lateinit var appComponent: AppComponent

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG_MODE) Log.w(TAG, "Thread [${Thread.currentThread().name}] onCreate: Start")

        // Turning on strict mode
        if (BuildConfig.DEBUG_MODE) {
            StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder()
                    .detectAll()
                    .detectCustomSlowCalls()
                    .permitDiskReads()
                    .permitDiskWrites()
                    .detectNetwork()
                    .penaltyLog()
                    .penaltyFlashScreen()
                    .build())

            StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder()
                    .detectActivityLeaks()
                    .detectLeakedClosableObjects()
                    .penaltyLog()
                    .build())
        }

        if (LeakCanary.isInAnalyzerProcess(this)) {
            // This process is dedicated to LeakCanary for heap analysis.
            // You should not initAppState your app in this process.
            return
        }
        LeakCanary.install(this)

        appComponent = DaggerAppComponent.builder().appModule(AppModule(this)).build()

        if (BuildConfig.DEBUG_MODE) Log.w(TAG, "Thread [${Thread.currentThread().name}] onCreate: End")
    }

    fun appComponent(): AppComponent = appComponent
}