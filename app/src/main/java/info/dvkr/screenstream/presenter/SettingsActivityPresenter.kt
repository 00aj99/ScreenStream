package info.dvkr.screenstream.presenter


import info.dvkr.screenstream.BuildConfig
import info.dvkr.screenstream.dagger.PersistentScope
import info.dvkr.screenstream.model.EventBus
import info.dvkr.screenstream.model.ImageNotify
import info.dvkr.screenstream.model.Settings
import info.dvkr.screenstream.ui.SettingsActivityView
import rx.Scheduler
import rx.subscriptions.CompositeSubscription
import java.net.ServerSocket
import javax.inject.Inject

@PersistentScope
class SettingsActivityPresenter @Inject internal constructor(private val settings: Settings,
                                                             private val eventScheduler: Scheduler,
                                                             private val eventBus: EventBus) {
    private val TAG = "SettingsActivityPresenter"

    private val subscriptions = CompositeSubscription()
    private var settingsActivity: SettingsActivityView? = null

    init {
        if (BuildConfig.DEBUG_MODE) println(TAG + ": Thread [${Thread.currentThread().name}] Constructor")
    }

    fun attach(activity: SettingsActivityView) {
        if (BuildConfig.DEBUG_MODE) println(TAG + ": Thread [${Thread.currentThread().name}] Attach")

        settingsActivity?.let { detach() }
        settingsActivity = activity

        // Setting current values
        settingsActivity?.let {
            it.toEvent(SettingsActivityView.ToEvent.MinimizeOnStream(settings.minimizeOnStream))
            it.toEvent(SettingsActivityView.ToEvent.StopOnSleep(settings.stopOnSleep))
            it.toEvent(SettingsActivityView.ToEvent.StartOnBoot(settings.startOnBoot))
            it.toEvent(SettingsActivityView.ToEvent.DisableMjpegCheck(settings.disableMJPEGCheck))
            it.toEvent(SettingsActivityView.ToEvent.HtmlBackColor(settings.htmlBackColor))
            it.toEvent(SettingsActivityView.ToEvent.ResizeFactor(settings.resizeFactor))
            it.toEvent(SettingsActivityView.ToEvent.JpegQuality(settings.jpegQuality))
            it.toEvent(SettingsActivityView.ToEvent.EnablePin(settings.enablePin))
            it.toEvent(SettingsActivityView.ToEvent.HidePinOnStart(settings.hidePinOnStart))
            it.toEvent(SettingsActivityView.ToEvent.NewPinOnAppStart(settings.newPinOnAppStart))
            it.toEvent(SettingsActivityView.ToEvent.AutoChangePin(settings.autoChangePin))
            it.toEvent(SettingsActivityView.ToEvent.SetPin(settings.currentPin))
            it.toEvent(SettingsActivityView.ToEvent.UseWiFiOnly(settings.useWiFiOnly))
            it.toEvent(SettingsActivityView.ToEvent.ServerPort(settings.severPort))
        }

        // Getting values from Activity
        settingsActivity?.fromEvent()?.observeOn(eventScheduler)?.subscribe { fromEvent ->
            if (BuildConfig.DEBUG_MODE) println(TAG + ": Thread [${Thread.currentThread().name}] fromEvent: $fromEvent")

            when (fromEvent) {
                is SettingsActivityView.FromEvent.MinimizeOnStream -> settings.minimizeOnStream = fromEvent.value
                is SettingsActivityView.FromEvent.StopOnSleep -> settings.stopOnSleep = fromEvent.value
                is SettingsActivityView.FromEvent.StartOnBoot -> settings.startOnBoot = fromEvent.value

                is SettingsActivityView.FromEvent.DisableMjpegCheck -> {
                    settings.disableMJPEGCheck = fromEvent.value
                    eventBus.sendEvent(EventBus.GlobalEvent.HttpServerRestart(ImageNotify.IMAGE_TYPE_RELOAD_PAGE))
                }

                is SettingsActivityView.FromEvent.HtmlBackColor -> {
                    settings.htmlBackColor = fromEvent.value
                    eventBus.sendEvent(EventBus.GlobalEvent.HttpServerRestart(ImageNotify.IMAGE_TYPE_RELOAD_PAGE))
                }

                is SettingsActivityView.FromEvent.ResizeFactor -> {
                    settings.resizeFactor = fromEvent.value
                    settingsActivity?.toEvent(SettingsActivityView.ToEvent.ResizeFactor(fromEvent.value))
                    eventBus.sendEvent(EventBus.GlobalEvent.ResizeFactor(fromEvent.value))
                }

                is SettingsActivityView.FromEvent.JpegQuality -> {
                    settings.jpegQuality = fromEvent.value
                    settingsActivity?.toEvent(SettingsActivityView.ToEvent.JpegQuality(fromEvent.value))
                    eventBus.sendEvent(EventBus.GlobalEvent.JpegQuality(fromEvent.value))
                }

                is SettingsActivityView.FromEvent.EnablePin -> {
                    settings.enablePin = fromEvent.value
                    eventBus.sendEvent(EventBus.GlobalEvent.HttpServerRestart(ImageNotify.IMAGE_TYPE_RELOAD_PAGE))
                    eventBus.sendEvent(EventBus.GlobalEvent.EnablePin(fromEvent.value))
                }

                is SettingsActivityView.FromEvent.HidePinOnStart -> settings.hidePinOnStart = fromEvent.value
                is SettingsActivityView.FromEvent.NewPinOnAppStart -> settings.newPinOnAppStart = fromEvent.value
                is SettingsActivityView.FromEvent.AutoChangePin -> settings.autoChangePin = fromEvent.value

                is SettingsActivityView.FromEvent.SetPin -> {
                    settings.currentPin = fromEvent.value
                    settingsActivity?.toEvent(SettingsActivityView.ToEvent.SetPin(fromEvent.value))
                    eventBus.sendEvent(EventBus.GlobalEvent.HttpServerRestart(ImageNotify.IMAGE_TYPE_RELOAD_PAGE))
                    eventBus.sendEvent(EventBus.GlobalEvent.SetPin(fromEvent.value))
                }

                is SettingsActivityView.FromEvent.UseWiFiOnly -> {
                    settings.useWiFiOnly = fromEvent.value
                    eventBus.sendEvent(EventBus.GlobalEvent.HttpServerRestart(ImageNotify.IMAGE_TYPE_NEW_ADDRESS))
                }

                is SettingsActivityView.FromEvent.ServerPort -> {
                    var serverSocket: ServerSocket? = null
                    var portFree = false
                    try {
                        serverSocket = ServerSocket(fromEvent.value)
                        portFree = true
                    } catch (ignore: Throwable) {
                        // Port is busy
                    } finally {
                        serverSocket?.close()
                        if (portFree) {
                            settings.severPort = fromEvent.value
                            settingsActivity?.toEvent(SettingsActivityView.ToEvent.ServerPort(fromEvent.value))
                            eventBus.sendEvent(EventBus.GlobalEvent.HttpServerRestart(ImageNotify.IMAGE_TYPE_NEW_ADDRESS))
                        } else {
                            settingsActivity?.toEvent(SettingsActivityView.ToEvent.ErrorServerPortBusy())
                            println(TAG + ": Thread [${Thread.currentThread().name}] ERROR: Port busy: ${fromEvent.value}")
                        }
                    }
                }

                else -> if (BuildConfig.DEBUG_MODE) println(TAG + ": Thread [${Thread.currentThread().name}] fromEvent: $fromEvent WARRING: IGNORED")
            }
        }.also { subscriptions.add(it) }
    }

    fun detach() {
        if (BuildConfig.DEBUG_MODE) println(TAG + ": Thread [${Thread.currentThread().name}] Detach")
        subscriptions.clear()
        settingsActivity = null
    }
}