package com.better.codingAlarm.wakelock

interface Wakelocks {
  fun acquireServiceLock()

  fun releaseServiceLock()
}
