package com.better.codingAlarm.test

import android.content.Intent
import androidx.test.espresso.Espresso.onData
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.longClick
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isCompletelyDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.better.codingAlarm.R
import com.better.codingAlarm.configuration.overrideIs24hoursFormatOverride
import com.better.codingAlarm.interfaces.PresentationToModelIntents
import com.better.codingAlarm.model.AlarmSetter
import com.better.codingAlarm.model.AlarmsReceiver
import com.better.codingAlarm.model.CalendarType
import com.better.codingAlarm.presenter.AlarmsListActivity
import com.better.codingAlarm.test.TestSync.Companion.clickFab
import java.util.*
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.allOf
import org.hamcrest.Matchers.anything
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ListTest {
  @JvmField var listActivity = ActivityScenarioRule(AlarmsListActivity::class.java)

  @JvmField
  @Rule
  var chain: TestRule = RuleChain.outerRule(ForceLocaleRule(Locale.US)).around(listActivity)

  @Before
  fun drop() {
    dropDatabase()
  }

  @After
  fun after() {
    dropDatabase()
  }

  @Test
  fun newAlarmShouldBeDisabledIfNotEdited() {
    clickFab()
    onView(withText("Cancel")).perform(click())
    onView(withText("OK")).perform(click())

    assertThat(alarmsList()).hasSize(3)

    assertThat(alarmsList().filter { it.isEnabled }).isEmpty()
  }

  @Test
  fun newAlarmShouldBeEnabledIfEdited12() {
    overrideIs24hoursFormatOverride(false)
    newAlarmShouldBeEnabledIfEdited()
  }

  @Test
  fun newAlarmShouldBeEnabledIfEdited24() {
    overrideIs24hoursFormatOverride(true)
    newAlarmShouldBeEnabledIfEdited()
  }

  private fun newAlarmShouldBeEnabledIfEdited() {
    clickFab()
    onView(withText("1")).perform(click())
    onView(withText("2")).perform(click())
    onView(withText("3")).perform(click())
    onView(withText("5")).perform(click())

    onView(withText("AM"))
        .withFailureHandler { _, _ ->
          // ignore fails - only use if View is found
        }
        .perform(click())

    onView(withText("OK")).perform(click())
    onView(withText("OK")).perform(click())

    assertThat(alarmsList()).hasSize(3)

    assertThat(alarmsList().filter { it.isEnabled }).hasSize(1)
  }

  @Test
  fun newAlarmShouldBeDisabledAfterDismiss() =
      runBlocking<Unit> {
        clickFab()
        onView(withText("1")).perform(click())
        onView(withText("2")).perform(click())
        onView(withText("3")).perform(click())
        onView(withText("5")).perform(click())

        onView(withText("AM"))
            .withFailureHandler { _, _ ->
              // ignore fails - only use if View is found
            }
            .perform(click())

        onView(withText("OK")).perform(click())
        onView(withText("OK")).perform(click())

        assertThat(alarmsList().filter { it.isEnabled }).hasSize(1)

        val id = alarmsList().first { it.isEnabled }.id

        fireAlarm(id)
        dismissAlarm(id)

        // listActivity.scenario.onActivity { it.store.uiVisible.filter { it }.blockingFirst() }
        println("Dismissed (done)")

        // alarm must be disabled because there is no repeating
        assertThat(alarmsList().filter { it.isEnabled }).isEmpty()
      }

  @Test
  fun editAlarmALot() {
    clickFab()
    assertTimerView("--:--")
    onView(withText("1")).perform(click())
    assertTimerView("--:-1")
    onView(withText("2")).perform(click())
    assertTimerView("--:12")
    onView(withText("3")).perform(click())
    assertTimerView("-1:23")
    onView(withText("5")).perform(click())
    assertTimerView("12:35")
    onView(withId(R.id.delete)).perform(click())
    assertTimerView("-1:23")
    onView(withId(R.id.delete)).perform(click())
    assertTimerView("--:12")
    onView(withId(R.id.delete)).perform(longClick())
    assertTimerView("--:--")
    onView(withText("Cancel")).perform(click())
    onView(withText("Cancel")).perform(click())
    assertThat(alarmsList()).hasSize(2)
  }

  @Test
  fun editRepeat() {
    clickFab()
    onView(withText("Cancel")).perform(click())

    onView(withText("Repeat")).perform(click())

    onView(allOf(isCompletelyDisplayed(), withText("Monday"))).perform(click())
    onView(allOf(isCompletelyDisplayed(), withText("Tuesday"))).perform(click())
    onView(allOf(isCompletelyDisplayed(), withText("Wednesday"))).perform(click())
    onView(allOf(isCompletelyDisplayed(), withText("Thursday"))).perform(click())
    onView(allOf(isCompletelyDisplayed(), withText("Friday"))).perform(click())
    onView(allOf(isCompletelyDisplayed(), withText("Saturday"))).perform(click())
    onView(allOf(isCompletelyDisplayed(), withText("Sunday"))).perform(click())
    onView(allOf(isCompletelyDisplayed(), withText("OK"))).perform(click())

    onView(withText("OK")).perform(click())

    assertThat(alarmsList()).hasSize(3)

    assertThat(alarmsList().filter { it.isEnabled }).hasSize(1)

    assertThat(alarmsList().filter { it.isRepeatSet }).hasSize(3)

    onView(withText("Every day")).check(matches(isDisplayed()))
  }

  @Test
  fun changeTimeInList() {
    onData(anything()).atPosition(0).onChildView(withId(R.id.digital_clock_time)).perform(click())

    onView(withText("1")).perform(click())
    onView(withText("2")).perform(click())
    onView(withText("3")).perform(click())
    onView(withText("5")).perform(click())

    onView(withText("AM"))
        .withFailureHandler { error, viewMatcher ->
          // ignore fails - only use if View is found
        }
        .perform(click())

    onView(withText("OK")).perform(click())

    assertThat(alarmsList().filter { it.isEnabled }).hasSize(1)

    onData(anything())
        .atPosition(0)
        .onChildView(withId(R.id.list_row_on_off_checkbox_container))
        .perform(click())
    assertThat(alarmsList().filter { it.isEnabled }).isEmpty()
  }

  /**
   * This is a test for https://github.com/yuriykulikov/AlarmClock/issues/361
   *
   * ## Given
   * * An alarm with Delete after dismissed activated
   *
   * ## When
   * * Alarm is fired
   * * Alarm is dismissed ## Then
   * * It should be deleted
   */
  @Test
  fun deleteOnDismiss() =
      runBlocking<Unit> {
        clickFab()
        onView(withText("1")).perform(click())
        onView(withText("2")).perform(click())
        onView(withText("3")).perform(click())
        onView(withText("5")).perform(click())

        onView(withText("AM"))
            .withFailureHandler { error, viewMatcher ->
              // ignore fails - only use if View is found
            }
            .perform(click())
        onView(withText("OK")).perform(click())
        onView(withText("OK")).perform(click())

        assertThat(alarmsList()).hasSize(3)
        assertThat(alarmsList().filter { it.isEnabled }).hasSize(1)
        assertThat(alarmsList().first { it.isEnabled }.isDeleteAfterDismiss).isTrue

        // when fired and dismissed
        val id = alarmsList().first { it.isEnabled }.id
        fireAlarm(id)
        dismissAlarm(id)

        // then alarm must be deleted from the list
        assertThat(alarmsList()).hasSize(2)
      }

  /**
   * This is a test for https://github.com/yuriykulikov/AlarmClock/issues/361
   *
   * ## Given
   * * An alarm with Delete after dismissed activated
   *
   * ## When
   * * Alarm is fired
   * * Alarm is dismissed ## Then
   * * It should be deleted
   */
  @Test
  fun deleteOnDismissDeactivated() =
      runBlocking<Unit> {
        clickFab()
        onView(withText("Cancel")).perform(click())
        onView(withText("Delete after dismissed")).perform(click())
        onView(withText("OK")).perform(click())

        assertThat(alarmsList()).hasSize(3)
        assertThat(alarmsList().filter { it.isEnabled }).hasSize(1)
        assertThat(alarmsList().first { it.isEnabled }.isDeleteAfterDismiss).isFalse

        // when fired and dismissed
        val id = alarmsList().first { it.isEnabled }.id
        fireAlarm(id)
        dismissAlarm(id)

        // then alarm must still be present in the list
        assertThat(alarmsList()).hasSize(3)
      }

  private fun fireAlarm(id: Int) {
    listActivity.scenario.onActivity { activity ->
      activity.sendBroadcast(
          Intent().apply {
            action = AlarmSetter.ACTION_FIRED
            setClass(activity, AlarmsReceiver::class.java)
            putExtra(AlarmSetter.EXTRA_ID, id)
            putExtra(AlarmSetter.EXTRA_TYPE, CalendarType.NORMAL.name)
          })
    }
  }

  private suspend fun dismissAlarm(id: Int) {
    sentTestIntent { context ->
      action = PresentationToModelIntents.ACTION_REQUEST_DISMISS
      setClass(context, AlarmsReceiver::class.java)
      putExtra(AlarmSetter.EXTRA_ID, id)
    }
  }
}
