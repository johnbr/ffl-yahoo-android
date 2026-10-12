package io.github.johnbr.fflyahoo

import android.app.Application
import io.github.johnbr.fflyahoo.data.EngineHub
import io.github.johnbr.fflyahoo.data.OkHttpFetcher
import io.github.johnbr.fflyahoo.data.SettingsStore
import io.github.johnbr.fflyahoo.live.LiveScheduler
import io.github.johnbr.fflyahoo.notify.Notifier
import io.github.johnbr.fflyahoo.widget.WidgetUpdater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** The app's object graph — small enough that a container beats a DI framework. */
class FflApp : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val fetcher by lazy { OkHttpFetcher() }
    val settings by lazy { SettingsStore(this) }
    val hub by lazy { EngineHub(this, settings, fetcher, scope) }
    val notifier by lazy { Notifier(this, hub, settings, scope) { scheduler } }
    val scheduler by lazy { LiveScheduler(this) }
    val widgets by lazy { WidgetUpdater(this, hub, settings, scope) }

    override fun onCreate() {
        super.onCreate()
        notifier.start()
        widgets.start()
    }
}

val android.content.Context.app: FflApp get() = applicationContext as FflApp
