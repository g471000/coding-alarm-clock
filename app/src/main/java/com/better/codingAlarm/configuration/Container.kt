package com.better.codingAlarm.configuration

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager
import android.os.PowerManager
import android.os.Vibrator
import android.telephony.TelephonyManager
import android.text.format.DateFormat
import com.better.codingAlarm.alert.BackgroundNotifications
import com.better.codingAlarm.background.AlertServicePusher
import com.better.codingAlarm.background.KlaxonPlugin
import com.better.codingAlarm.background.PlayerWrapper
import com.better.codingAlarm.bugreports.BugReporter
import com.better.codingAlarm.interfaces.IAlarmsManager
import com.better.codingAlarm.logger.Logger
import com.better.codingAlarm.logger.LoggerFactory
import com.better.codingAlarm.logger.loggerModule
import com.better.codingAlarm.model.AlarmCore
import com.better.codingAlarm.model.AlarmSetter
import com.better.codingAlarm.model.AlarmStateNotifier
import com.better.codingAlarm.model.Alarms
import com.better.codingAlarm.model.AlarmsRepository
import com.better.codingAlarm.model.AlarmsScheduler
import com.better.codingAlarm.model.Calendars
import com.better.codingAlarm.model.IAlarmsScheduler
import com.better.codingAlarm.persistance.DataStoreAlarmsRepository
import com.better.codingAlarm.persistance.DatabaseQuery
import com.better.codingAlarm.persistance.DatastoreMigration
import com.better.codingAlarm.persistance.SQLiteDatabaseQuery
import com.better.codingAlarm.presenter.AlarmsListActivity
import com.better.codingAlarm.presenter.DynamicThemeHandler
import com.better.codingAlarm.presenter.ScheduledReceiver
import com.better.codingAlarm.presenter.ToastPresenter
import com.better.codingAlarm.stores.SharedRxDataStoreFactory
import com.better.codingAlarm.util.Optional
import com.better.codingAlarm.wakelock.WakeLockManager
import com.better.codingAlarm.wakelock.Wakelocks
import io.reactivex.Observable
import io.reactivex.Scheduler
import io.reactivex.Single
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.subjects.BehaviorSubject
import io.reactivex.subjects.PublishSubject
import java.io.File
import java.util.*
import kotlinx.coroutines.Dispatchers
import org.koin.core.Koin
import org.koin.core.context.loadKoinModules
import org.koin.core.context.startKoin
import org.koin.core.qualifier.named
import org.koin.core.scope.Scope
import org.koin.dsl.binds
import org.koin.dsl.module

fun Scope.logger(tag: String): Logger {
  return get<LoggerFactory>().createLogger(tag)
}

fun Koin.logger(tag: String): Logger {
  return get<LoggerFactory>().createLogger(tag)
}

fun startKoin(context: Context): Koin {
  // The following line triggers the initialization of ACRA

  val module = module {
    single<DynamicThemeHandler> { DynamicThemeHandler(get()) }
    single<BugReporter> { BugReporter(logger("BugReporter"), context) }
    factory<Context> { context }
    factory(named("dateFormatOverride")) { "none" }
    factory<Single<Boolean>>(named("dateFormat")) {
      Single.fromCallable {
        get<String>(named("dateFormatOverride")).let { if (it == "none") null else it.toBoolean() }
            ?: DateFormat.is24HourFormat(context)
      }
    }

    single<Prefs> {
      val factory = SharedRxDataStoreFactory.create(get(), logger("preferences"))
      Prefs.create(get(named("dateFormat")), factory)
    }

    single<Store> {
      Store(
          alarmsSubject = BehaviorSubject.createDefault(ArrayList()),
          next = BehaviorSubject.createDefault<Optional<Store.Next>>(Optional.absent()),
          sets = PublishSubject.create(),
          events = PublishSubject.create())
    }

    factory { get<Context>().getSystemService(Context.ALARM_SERVICE) as AlarmManager }
    single<AlarmSetter> { AlarmSetter.AlarmSetterImpl(logger("AlarmSetter"), get(), get()) }
    factory { Calendars { Calendar.getInstance() } }
    single<AlarmsScheduler> {
      AlarmsScheduler(get(), logger("AlarmsScheduler"), get(), get(), get())
    }
    factory<IAlarmsScheduler> { get<AlarmsScheduler>() }
    single<AlarmCore.IStateNotifier> { AlarmStateNotifier(get()) }
    single<AlarmsRepository> {
      DataStoreAlarmsRepository(
          datastoreDir = get(named("datastore")),
          logger = logger("DataStoreAlarmsRepository"),
          ioDispatcher = Dispatchers.IO,
      )
    }
    single(named("datastore")) { File(get<Context>().applicationContext.filesDir, "datastore") }
    factory { get<Context>().contentResolver }
    single<DatabaseQuery> { SQLiteDatabaseQuery(get()) }
    single { Alarms(get(), get(), get(), get(), get(), get(), logger("Alarms"), get()) } binds
        arrayOf(IAlarmsManager::class, DatastoreMigration::class)
    single { ScheduledReceiver(get(), get(), get(), get()) }
    single { ToastPresenter(get(), get()) }
    single { AlertServicePusher(get(), get(), get(), logger("AlertServicePusher")) }
    single { BackgroundNotifications(get(), get(), get(), get(), get()) }
    factory<Wakelocks> { get<WakeLockManager>() }
    factory<Scheduler> { AndroidSchedulers.mainThread() }
    single { WakeLockManager(logger("WakeLockManager"), get()) }
    factory { get<Context>().getSystemService(Context.VIBRATOR_SERVICE) as Vibrator }
    factory { get<Context>().getSystemService(Context.POWER_SERVICE) as PowerManager }
    factory { get<Context>().getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager }
    factory { get<Context>().getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager }
    factory { get<Context>().getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    factory { get<Context>().resources }

    factory(named("volumePreferenceDemo")) {
      KlaxonPlugin(
          log = logger("VolumePreference"),
          playerFactory = { PlayerWrapper(get(), get(), logger("VolumePreference")) },
          prealarmVolume = get<Prefs>().preAlarmVolume.observe(),
          fadeInTimeInMillis = Observable.just(100),
          inCall = Observable.just(false),
          scheduler = get())
    }
  }

  return startKoin {
        modules(module)
        modules(AlarmsListActivity.uiStoreModule)
        modules(loggerModule())
      }
      .koin
}

fun overrideIs24hoursFormatOverride(is24hours: Boolean) {
  loadKoinModules(
      module =
          module(override = true) { factory(named("dateFormatOverride")) { is24hours.toString() } })
}
