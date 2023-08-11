/*
 * Copyright (C) 2017 Yuriy Kulikov yuriy.kulikov.87@gmail.com
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

package com.better.codingAlarm.presenter

import android.content.Intent
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.NumberPicker
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.better.codingAlarm.R
import com.better.codingAlarm.checkPermissions
import com.better.codingAlarm.configuration.Layout
import com.better.codingAlarm.configuration.Prefs
import com.better.codingAlarm.configuration.globalInject
import com.better.codingAlarm.configuration.globalLogger
import com.better.codingAlarm.interfaces.IAlarmsManager
import com.better.codingAlarm.logger.Logger
import com.better.codingAlarm.lollipop
import com.better.codingAlarm.model.AlarmValue
import com.better.codingAlarm.model.Alarmtone
import com.better.codingAlarm.model.ringtoneManagerString
import com.better.codingAlarm.question.QuestionType
import com.better.codingAlarm.util.Optional
import com.better.codingAlarm.util.modify
import com.better.codingAlarm.view.showRepeatAndDateDialog
import io.reactivex.Observable
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.disposables.Disposable
import io.reactivex.disposables.Disposables
import io.reactivex.schedulers.Schedulers
import java.text.SimpleDateFormat
import java.util.*


/** Details activity allowing for fine-grained alarm modification */
class AlarmDetailsFragment : Fragment() {
    private val alarms: IAlarmsManager by globalInject()
    private val logger: Logger by globalLogger("AlarmDetailsFragment")
    private val prefs: Prefs by globalInject()
    private var disposables = CompositeDisposable()

    private var backButtonSub: Disposable = Disposables.disposed()
    private var disposableDialog = Disposables.disposed()

    private val alarmsListActivity by lazy { activity as AlarmsListActivity }
    private val store: UiStore by globalInject()

    private val rowHolder: RowHolder by lazy {
        RowHolder(fragmentView.findViewById(R.id.details_list_row_container), alarmId, prefs.layout())
    }

    private val editor: Observable<AlarmValue> by lazy {
        store.editing().filter { it.value.isPresent() }.map { it.value.get() }
    }

    private val alarmId: Int by lazy { store.editing().value!!.id }

    private val highlighter: ListRowHighlighter? by lazy {
        ListRowHighlighter.createFor(requireActivity().theme)
    }

    private lateinit var fragmentView: View

    private val ringtonePickerRequestCode = 42

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        logger.trace { "Showing details of ${store.editing().value}" }

        val view =
            inflater.inflate(
                when (prefs.layout()) {
                    Layout.CLASSIC -> R.layout.details_fragment_classic
                    Layout.COMPACT -> R.layout.details_fragment_compact
                    else -> R.layout.details_fragment_bold
                },
                container,
                false
            )
        this.fragmentView = view

        disposables = CompositeDisposable()

        onCreateTopRowView()
        onCreateRepeatView()
        onCreateRingtoneView()
        onCreateDeleteOnDismissView()
        onCreatePrealarmView()
        onCreateBottomView()
        onCreateQuestionSelectView()

        store.transitioningToNewAlarmDetails().takeFirst { isNewAlarm ->
            if (isNewAlarm) {
                showTimePicker()
            }
            store.transitioningToNewAlarmDetails().onNext(false)
        }

        return view
    }

    private fun onCreateBottomView() {
        fragmentView.findViewById<View>(R.id.details_activity_button_save).setOnClickListener {
            saveAlarm()
        }
        fragmentView.findViewById<View>(R.id.details_activity_button_revert).setOnClickListener {
            revert()
        }
    }


    private fun onCreateRepeatView() {
        fragmentView.findViewById<LinearLayout>(R.id.details_repeat_row).setOnClickListener {
            editor
                .firstOrError()
                .flatMap { value -> requireContext().showRepeatAndDateDialog(value) }
                .subscribe { fromDialog ->
                    modify("Repeat dialog") { prev ->
                        prev.copy(
                            isEnabled = true, daysOfWeek = fromDialog.daysOfWeek, date = fromDialog.date
                        )
                    }
                }
                .addTo(disposables)
        }

        val repeatTitle = fragmentView.findViewById<TextView>(R.id.details_repeat_title)
        val repeatSummary = fragmentView.findViewById<TextView>(R.id.details_repeat_summary)

        observeEditor { value ->
            repeatTitle.text =
                when {
                    value.date != null -> requireContext().getString(R.string.date)
                    else -> requireContext().getString(R.string.alarm_repeat)
                }

            repeatSummary.text =
                when {
                    value.date != null -> SimpleDateFormat.getDateInstance().format(value.date.time)
                    else -> value.daysOfWeek.toString(requireContext(), true)
                }
        }

        observeEditor { alarmValue ->
            val valid = alarmValue.isValid()
            fragmentView.findViewById<View>(R.id.details_activity_button_save).isEnabled = valid
            rowHolder.rowView.isEnabled = valid
            rowHolder.detailsCheckImageView.alpha = if (valid) 1f else .2f

            repeatSummary.setTextColor(
                requireActivity()
                    .theme
                    .resolveColor(if (valid) android.R.attr.colorForeground else R.attr.colorError)
            )
        }
    }

    private fun AlarmValue.isValid(): Boolean {
        return when (date) {
            null -> true
            else -> {
                val nextTime =
                    Calendar.getInstance().apply {
                        timeInMillis = date.timeInMillis
                        set(Calendar.HOUR_OF_DAY, hour)
                        set(Calendar.MINUTE, minutes)
                    }
                nextTime.after(Calendar.getInstance())
            }
        }
    }

    private fun onCreateDeleteOnDismissView() {
        val mDeleteOnDismissRow by lazy {
            fragmentView.findViewById(R.id.details_delete_on_dismiss_row) as LinearLayout
        }

        val mDeleteOnDismissCheckBox by lazy {
            fragmentView.findViewById(R.id.details_delete_on_dismiss_checkbox) as CheckBox
        }

        mDeleteOnDismissRow.setOnClickListener {
            modify("Delete on Dismiss") { value ->
                value.copy(isDeleteAfterDismiss = !value.isDeleteAfterDismiss, isEnabled = true)
            }
        }

        observeEditor { value ->
            mDeleteOnDismissCheckBox.isChecked = value.isDeleteAfterDismiss
            mDeleteOnDismissRow.visibility = if (value.isRepeatSet) View.GONE else View.VISIBLE
        }
    }


    private fun onCreateQuestionSelectView() {
        val mQuestionTypeSpinner by lazy {
            fragmentView.findViewById(R.id.question_type_dropdown) as Spinner
        }

        val mQuestionNumberPicker by lazy {
            fragmentView.findViewById(R.id.question_count_picker) as NumberPicker
        }
        mQuestionNumberPicker.minValue = 1 // 최소 값 설정
        mQuestionNumberPicker.maxValue = 5 // 최대 값 설정
        mQuestionNumberPicker.value = 1 // 초기 값을 설정


        mQuestionTypeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selectedQuestionType = when (parent?.getItemAtPosition(position).toString()) {
                    "ALL" -> QuestionType.ALL
                    "Java" -> QuestionType.JAVA
                    "Python" -> QuestionType.PYTHON
                    "SQL" -> QuestionType.SQL
                    "CODING_INTERVIEW" -> QuestionType.CODING_INTERVIEW
                    else -> QuestionType.ALL // default
                }
                modify(
                    "Question Type"
                ) {value -> value.copy(questionType = selectedQuestionType)}
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {
                val selectedQuestionType = QuestionType.ALL

                modify(
                    "Question Type"
                ) {value -> value.copy(questionType = selectedQuestionType)}
            }

        }
    }

    private fun onCreatePrealarmView() {
        val mPreAlarmRow by lazy {
            fragmentView.findViewById(R.id.details_prealarm_row) as LinearLayout
        }

        val mPreAlarmCheckBox by lazy {
            fragmentView.findViewById(R.id.details_prealarm_checkbox) as CheckBox
        }

        // pre-alarm
        mPreAlarmRow.setOnClickListener {
            modify("Pre-alarm") { value -> value.copy(isPrealarm = !value.isPrealarm, isEnabled = true) }
        }

        observeEditor { value -> mPreAlarmCheckBox.isChecked = value.isPrealarm }

        // pre-alarm duration, if set to "none", remove the option
        prefs.preAlarmDuration
            .observe()
            .subscribe { value ->
                mPreAlarmRow.visibility = if (value.toInt() == -1) View.GONE else View.VISIBLE
            }
            .addTo(disposables)
    }

    private fun onCreateRingtoneView() {
        fragmentView.findViewById<LinearLayout>(R.id.details_ringtone_row).setOnClickListener {
            editor.takeFirst { value ->
                try {
                    val pickerIntent =
                        Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                            putExtra(
                                RingtoneManager.EXTRA_RINGTONE_EXISTING_URI,
                                value.alarmtone.ringtoneManagerString()
                            )

                            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                            putExtra(
                                RingtoneManager.EXTRA_RINGTONE_DEFAULT_URI,
                                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                            )

                            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true)
                            putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
                        }
                    startActivityForResult(pickerIntent, ringtonePickerRequestCode)
                } catch (e: Exception) {
                    Toast.makeText(
                        context,
                        requireContext().getString(R.string.details_no_ringtone_picker),
                        Toast.LENGTH_LONG
                    )
                        .show()
                }
            }
        }

        val ringtoneSummary by lazy {
            fragmentView.findViewById<TextView>(R.id.details_ringtone_summary)
        }
        editor
            .distinctUntilChanged()
            .observeOn(Schedulers.computation())
            .map { value ->
                when (value.alarmtone) {
                    is Alarmtone.Silent -> requireContext().getText(R.string.silent_alarm_summary)
                    is Alarmtone.Default ->
                        RingtoneManager.getRingtone(
                            context, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                        )
                            .title()
                    is Alarmtone.Sound ->
                        RingtoneManager.getRingtone(context, Uri.parse(value.alarmtone.uriString)).title()
                }
            }
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe { ringtoneSummary.text = it }
            .addTo(disposables)
    }

    private fun onCreateTopRowView() =
        rowHolder.apply {
            daysOfWeek.visibility = View.INVISIBLE
            label.visibility = View.INVISIBLE

            lollipop {
                digitalClock.transitionName = "clock$alarmId"
                container.transitionName = "onOff$alarmId"
                detailsButton.transitionName = "detailsButton$alarmId"
            }

            digitalClock.setLive(false)

            val pickerClickTarget =
                if (layout == Layout.CLASSIC) digitalClockContainer else digitalClock

            container.setOnClickListener {
                modify("onOff") { value -> value.copy(isEnabled = !value.isEnabled) }
            }

            pickerClickTarget.setOnClickListener { showTimePicker() }

            rowView.setOnClickListener { saveAlarm() }

            observeEditor { value ->
                rowHolder.digitalClock.updateTime(
                    Calendar.getInstance().apply {
                        set(Calendar.HOUR_OF_DAY, value.hour)
                        set(Calendar.MINUTE, value.minutes)
                    })

                rowHolder.onOff.isChecked = value.isEnabled

                highlighter?.applyTo(rowHolder, value.isEnabled)
            }

            animateCheck(check = true)
        }

    override fun onDestroyView() {
        super.onDestroyView()
        disposables.dispose()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (data != null && requestCode == ringtonePickerRequestCode) {
            handlerRingtonePickerResult(data)
        }
    }

    private fun handlerRingtonePickerResult(data: Intent) {
        val alert: String? =
            data.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)?.toString()

        logger.debug { "Got ringtone: $alert" }

        val alarmtone =
            when (alert) {
                null -> Alarmtone.Silent
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM).toString() -> Alarmtone.Default
                else -> Alarmtone.Sound(alert)
            }

        logger.debug { "onActivityResult $alert -> $alarmtone" }

        checkPermissions(requireActivity(), listOf(alarmtone))

        modify("Ringtone picker") { prev -> prev.copy(alarmtone = alarmtone, isEnabled = true) }
    }

    fun Ringtone?.title(): CharSequence {
        return try {
            context?.let { this?.getTitle(it) } ?: context?.getText(R.string.silent_alarm_summary)
        } catch (e: Exception) {
            context?.getText(R.string.silent_alarm_summary)
        } catch (e: NullPointerException) {
            null
        } ?: ""
    }

    override fun onResume() {
        super.onResume()
        backButtonSub =
            store.onBackPressed().subscribe {
                editor.takeFirst {
                    if (it.isValid()) {
                        saveAlarm()
                    }
                }
            }
    }

    override fun onPause() {
        super.onPause()
        disposableDialog.dispose()
        backButtonSub.dispose()
    }

    private fun saveAlarm() {
        editor.takeFirst { value ->
            alarms.getAlarm(alarmId)?.run { edit { withChangeData(value) } }
            store.hideDetails(rowHolder)
            animateCheck(check = false)
        }
    }

    private fun revert() {
        store.editing().value?.let { edited ->
            // "Revert" on a newly created alarm should delete it.
            if (edited.isNew) {
                alarms.getAlarm(edited.id)?.delete()
            }
            // else do not save changes
            store.hideDetails(rowHolder)
            animateCheck(check = false)
        }
    }

    private fun showTimePicker() {
        disposableDialog =
            TimePickerDialogFragment.showTimePicker(alarmsListActivity.supportFragmentManager)
                .subscribe { picked: Optional<PickedTime> ->
                    if (picked.isPresent()) {
                        modify("Picker") { value ->
                            value.copy(
                                hour = picked.get().hour, minutes = picked.get().minute, isEnabled = true
                            )
                        }
                    }
                }
    }

    private fun modify(reason: String, function: (AlarmValue) -> AlarmValue) {
        logger.debug { "Performing modification because of $reason" }
        store.editing().modify { copy(value = value.map { function(it) }) }
    }

    private fun Disposable.addTo(disposables: CompositeDisposable) {
        disposables.add(this)
    }

    private fun animateCheck(check: Boolean) {
        rowHolder.detailsCheckImageView.animate().alpha(if (check) 1f else 0f).setDuration(500).start()
        rowHolder.detailsImageView.animate().alpha(if (check) 0f else 1f).setDuration(500).start()
    }

    private fun observeEditor(block: (value: AlarmValue) -> Unit) {
        editor.distinctUntilChanged().subscribe { block(it) }.addTo(disposables)
    }

    private fun <T : Any> Observable<T>.takeFirst(block: (value: T) -> Unit) {
        take(1).subscribe { block(it) }.addTo(disposables)
    }
}
