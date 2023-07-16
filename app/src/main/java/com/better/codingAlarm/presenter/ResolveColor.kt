package com.better.codingAlarm.presenter

import android.content.res.Resources
import android.util.TypedValue

fun Resources.Theme.resolveColor(color: Int): Int {
  return TypedValue().also { resolveAttribute(color, it, true) }.data
}
