package com.better.codingAlarm.interfaces;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import com.better.codingAlarm.BuildConfig;
import com.better.codingAlarm.OreoKt;
import com.better.codingAlarm.model.AlarmsReceiver;

public class PresentationToModelIntents {

  public static final String ACTION_REQUEST_SNOOZE =
      BuildConfig.APPLICATION_ID + ".model.interfaces.ServiceIntents.ACTION_REQUEST_SNOOZE";
  public static final String ACTION_REQUEST_DISMISS =
      BuildConfig.APPLICATION_ID + ".model.interfaces.ServiceIntents.ACTION_REQUEST_DISMISS";
  public static final String ACTION_REQUEST_SKIP =
      BuildConfig.APPLICATION_ID + ".model.interfaces.ServiceIntents.ACTION_REQUEST_SKIP";

  public static PendingIntent createPendingIntent(Context context, String action, int id) {
    Intent intent = new Intent(action);
    intent.putExtra(Intents.EXTRA_ID, id);
    intent.setClass(context, AlarmsReceiver.class);
    return PendingIntent.getBroadcast(context, id, intent, OreoKt.pendingIntentUpdateCurrentFlag());
  }
}
