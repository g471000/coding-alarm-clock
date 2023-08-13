/*
 * Copyright (C) 2009 The Android Open Source Project
 * Copyright (C) 2012 Yuriy Kulikov yuriy.kulikov.87@gmail.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.better.codingAlarm.alert

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.better.codingAlarm.R
import com.better.codingAlarm.background.Event.Autosilenced
import com.better.codingAlarm.background.Event.DemuteEvent
import com.better.codingAlarm.background.Event.DismissEvent
import com.better.codingAlarm.background.Event.MuteEvent
import com.better.codingAlarm.background.Event.SnoozedEvent
import com.better.codingAlarm.configuration.AlarmApplication
import com.better.codingAlarm.configuration.Prefs
import com.better.codingAlarm.configuration.Store
import com.better.codingAlarm.configuration.globalInject
import com.better.codingAlarm.configuration.globalLogger
import com.better.codingAlarm.interfaces.Alarm
import com.better.codingAlarm.interfaces.IAlarmsManager
import com.better.codingAlarm.interfaces.Intents
import com.better.codingAlarm.question.Question
import com.better.codingAlarm.question.QuestionList
import com.better.codingAlarm.presenter.DynamicThemeHandler
import com.better.codingAlarm.presenter.TimePickerDialogFragment
import io.reactivex.Observable
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.disposables.Disposable
import io.reactivex.disposables.Disposables
import java.util.concurrent.TimeUnit

/**
 * Alarm Clock alarm alert: pops visible indicator and plays alarm tone. This activity is the full
 * screen version which shows over the lock screen with the wallpaper as the background.
 */
class AlarmAlertFullScreen : FragmentActivity() {
    private val store: Store by globalInject()
    private val alarmsManager: IAlarmsManager by globalInject()
    private val sp: Prefs by globalInject()
    private val logger by globalLogger("AlarmAlertFullScreen")
    private val dynamicThemeHandler: DynamicThemeHandler by globalInject()
    private var mAlarm: Alarm? = null
    private var disposableDialog = Disposables.empty()
    private var subscription: Disposable? = null
    private var question: Question? = null
    private var correctAnswerCount: Int = 0

    override fun onCreate(icicle: Bundle?) {
        AlarmApplication.startOnce(application)
        setTheme(dynamicThemeHandler.alertTheme())

        super.onCreate(icicle)
        requestedOrientation =
            when {
                // portrait on smartphone
                !resources.getBoolean(R.bool.isTablet) -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                // preserve initial rotation and disable rotation change on tablets
                resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT ->
                    requestedOrientation

                else -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }
        val id = intent.getIntExtra(Intents.EXTRA_ID, -1)

        mAlarm = alarmsManager.getAlarm(id)

        turnScreenOn()
        updateLayout()

        // Register to get the alarm killed/snooze/dismiss intent.
        subscription =
            store.events
                .filter { event ->
                    (event is SnoozedEvent && event.id == id ||
                        event is DismissEvent && event.id == id ||
                        event is Autosilenced && event.id == id)
                }
                .take(1)
                .subscribe { finish() }
        question = QuestionList().getRandomQuestion(mAlarm?.data!!.questionType)
        setQuestion()

    }

    /**
     * ## Turns the screen on
     *
     * See https://github.com/yuriykulikov/AlarmClock/issues/360 It seems that on some devices with
     * API>=27 calling `setTurnScreenOn(true)` is not enough, so we will just add all flags regardless
     * of the API level, and call `setTurnScreenOn(true)` if API level is 27+
     *
     * ### 3.07.01 reference In `3.07.01` we added these 4 flags:
     * ```
     * final Window win = getWindow();
     * win.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED);
     * win.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
     *         | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
     *         | WindowManager.LayoutParams.FLAG_ALLOW_LOCK_WHILE_SCREEN_ON);
     * ```
     */
    private fun turnScreenOn() {
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        // Deprecated flags are required on some devices, even with API>=27
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_ALLOW_LOCK_WHILE_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        )
    }

    private fun setQuestion() {
        // Question Setting
        val questionText = question?.description
        findViewById<TextView>(R.id.alarm_question).text = questionText

        // 정답 세팅
        val choices = question?.choices;
        findViewById<TextView>(R.id.alert_button_one).text = choices?.get(0) ?: "1";
        findViewById<TextView>(R.id.alert_button_two).text = choices?.get(1) ?: "2";
        findViewById<TextView>(R.id.alert_button_three).text = choices?.get(2) ?: "3";
        findViewById<TextView>(R.id.alert_button_four).text = choices?.get(3) ?: "4";

        // 맞춘 정답 개수 세팅
        val questionCount = mAlarm?.data!!.questionCount
        findViewById<TextView>(R.id.correct_answer_count).text = correctAnswerCount.toString()
        findViewById<TextView>(R.id.required_correct_answer_count).text = questionCount.toString()

    }

    private fun updateLayout() {
        setContentView(R.layout.alert_fullscreen)
        setQuestion()

        setButtonBehavior(R.id.alert_button_one, 0)
        setButtonBehavior(R.id.alert_button_two, 1)
        setButtonBehavior(R.id.alert_button_three, 2)
        setButtonBehavior(R.id.alert_button_four, 3)
    }


    private fun setButtonBehavior(buttonId: Int, answerIndex: Int) {
        val button = findViewById<Button>(buttonId)
        val questionCount = mAlarm?.data!!.questionCount

        val defaultButtonBackground = button.background // Store the default button background

        button.setOnClickListener {
            val isCorrect = question?.correctAnswer == answerIndex

            if (isCorrect) {
                correctAnswerCount++

                if (questionCount == correctAnswerCount) {
                    mAlarm?.dismiss()
                }
            }

            button.setBackgroundResource(if (isCorrect) R.drawable.button_correct_background else R.drawable.button_incorrect_background)
            button.setTextColor(ContextCompat.getColor(this, R.color.white))

            // Highlight the correct answer button if the selected answer is incorrect
            val correctButtonId = when (question?.correctAnswer) {
                0 -> R.id.alert_button_one
                1 -> R.id.alert_button_two
                2 -> R.id.alert_button_three
                3 -> R.id.alert_button_four
                else -> -1
            }

            val correctButton = findViewById<Button>(correctButtonId)
            correctButton.setBackgroundResource(R.drawable.button_correct_background)

            // 텍스트 설정 및 1초 후에 다음 문제로 넘어가기
            button.isEnabled = false
            Handler(Looper.getMainLooper()).postDelayed({
                // Question 다시 뽑아서 세팅
                question = QuestionList().getRandomQuestion(mAlarm?.data!!.questionType)
                setQuestion()

                // 버튼 색 다시 default로 조정
                button.background = defaultButtonBackground // Reset button background
                correctButton.background = defaultButtonBackground // Reset correct answer button background
                button.setTextColor(ContextCompat.getColor(this, R.color.dark_on_background))
                correctButton.setTextColor(ContextCompat.getColor(this, R.color.dark_on_background))
                button.isEnabled = true
            }, 2000)
        }
    }


    /**
     * Shows a time picker to pick the next snooze time. Mutes the sound for the first 10 seconds to
     * let the user choose the time. Demutes after cancel or after 10 seconds to deal with
     * unintentional clicks.
     */
    private fun showSnoozePicker() {
        store.events.onNext(MuteEvent())
        val timer =
            Observable.timer(10, TimeUnit.SECONDS, AndroidSchedulers.mainThread()).subscribe {
                store.events.onNext(DemuteEvent())
            }

        val dialog =
            TimePickerDialogFragment.showTimePicker(supportFragmentManager).subscribe { picked ->
                timer.dispose()
                if (picked.isPresent()) {
                    mAlarm?.snooze(picked.get().hour, picked.get().minute)
                } else {
                    store.events.onNext(DemuteEvent())
                }
            }

        disposableDialog = CompositeDisposable(dialog, timer)
    }

    private fun dismiss() {
        mAlarm?.dismiss()
    }

    private val isSnoozeEnabled: Boolean
        get() = sp.snoozeDuration.value != -1

    /**
     * this is called when a second alarm is triggered while a previous alert window is still active.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        logger.debug { "AlarmAlert.OnNewIntent()" }
        val id = intent.getIntExtra(Intents.EXTRA_ID, -1)
        mAlarm = alarmsManager.getAlarm(id)
        setQuestion()
    }

    override fun onResume() {
        super.onResume()
//        findViewById<Button>(R.id.alert_button_snooze)?.isEnabled = isSnoozeEnabled
//        findViewById<View>(R.id.alert_text_snooze)?.isEnabled = isSnoozeEnabled
    }

    override fun onPause() {
        super.onPause()
        disposableDialog.dispose()
    }

    public override fun onDestroy() {
        super.onDestroy()
        // No longer care about the alarm being killed.
        subscription?.dispose()
        disposableDialog.dispose()
    }

    override fun onBackPressed() {
        // Don't allow back to dismiss
    }
}
