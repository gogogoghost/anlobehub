package cc.jaxy.anlobehub.app

import android.app.Activity
import android.app.Application
import android.os.Bundle
import cc.jaxy.anlobehub.core.common.preferences.AppLanguage
import cc.jaxy.anlobehub.core.data.preferences.UiPreferencesStore
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import okio.Path.Companion.toOkioPath
import dagger.hilt.android.HiltAndroidApp
import java.lang.ref.WeakReference
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

@HiltAndroidApp
class AnlobehubApp : Application() {

    @Inject
    lateinit var uiPreferences: UiPreferencesStore

    @Inject
    lateinit var okHttpClient: OkHttpClient

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var foregroundActivity = WeakReference<Activity>(null)
    private var appliedTag: String? = null

    override fun onCreate() {
        super.onCreate()
        // Coil3 needs an explicit network fetcher for http(s) images.
        SingletonImageLoader.setSafe {
            ImageLoader.Builder(this)
                .components { add(OkHttpNetworkFetcherFactory(okHttpClient)) }
                // Avatars/logos are tiny and URL-addressed (new URL = new
                // image), so cache aggressively: 25% RAM + 100MB disk.
                .memoryCache {
                    MemoryCache.Builder()
                        .maxSizePercent(this, 0.25)
                        .build()
                }
                .logger(coil3.util.DebugLogger())
                .diskCache {
                    DiskCache.Builder()
                        .directory(cacheDir.resolve("coil").toOkioPath())
                        .maxSizeBytes(100L * 1024 * 1024)
                        .build()
                }
                .build()
        }
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                foregroundActivity = WeakReference(activity)
            }

            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
        // Recreate the foreground activity when the language changes at runtime.
        // Startup locale is handled by MainActivity.attachBaseContext.
        appScope.launch {
            uiPreferences.language
                .distinctUntilChanged()
                .drop(1)
                .collect { language ->
                    applyLanguage(language)
                }
        }
    }

    private fun applyLanguage(language: AppLanguage) {
        // drop(1) already skipped the startup emission; every emission here
        // is a genuine runtime change → recreate to apply the new locale.
        if (appliedTag == language.tag) return
        appliedTag = language.tag
        foregroundActivity.get()?.recreate()
    }
}
