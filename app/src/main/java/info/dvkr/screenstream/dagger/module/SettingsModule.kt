package info.dvkr.screenstream.dagger.module

import android.content.Context
import android.preference.PreferenceManager
import android.util.Log
import com.ironz.binaryprefs.BinaryPreferencesBuilder
import dagger.Module
import dagger.Provides
import info.dvkr.screenstream.BuildConfig
import info.dvkr.screenstream.model.Settings
import info.dvkr.screenstream.model.settings.SettingsImpl
import javax.inject.Singleton


@Singleton
@Module(includes = arrayOf(AppModule::class))
class SettingsModule {

    @Provides
    @Singleton
    internal fun getSettingsHelper(context: Context): Settings {
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().apply()
        val preferences = BinaryPreferencesBuilder(context)
                .exceptionHandler {
                    it?.let {
                        if (BuildConfig.DEBUG_MODE) Log.e("BinaryPreferences", it.toString())
                    }
                }
                .build()
        return SettingsImpl(preferences)
    }
}