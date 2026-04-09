/*
 * Copyright (C) 2016-2025 Álinson Santos Xavier <git@axavier.org>
 *
 * This file is part of ADay.
 *
 * ADay is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by the
 * Free Software Foundation, either version 3 of the License, or (at your
 * option) any later version.
 *
 * ADay is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for
 * more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package org.bruce.aday.activities.habits.list

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import android.widget.FrameLayout
import android.widget.RelativeLayout
import nl.dionsegijn.konfetti.xml.KonfettiView
import org.bruce.aday.R
import org.bruce.aday.activities.common.views.ScrollableChart
import org.bruce.aday.activities.common.views.TaskProgressBar
import org.bruce.aday.activities.habits.list.views.EmptyListView
import org.bruce.aday.activities.habits.list.views.HabitCardListAdapter
import org.bruce.aday.activities.habits.list.views.HabitCardListView
import org.bruce.aday.activities.habits.list.views.HabitCardListViewFactory
import org.bruce.aday.activities.habits.list.views.HeaderView
import org.bruce.aday.activities.habits.list.views.HintView
import org.bruce.aday.core.models.ModelObservable
import org.bruce.aday.core.models.PaletteColor
import org.bruce.aday.core.preferences.Preferences
import org.bruce.aday.core.tasks.TaskRunner
import org.bruce.aday.core.ui.screens.habits.list.HintListFactory
import org.bruce.aday.core.utils.MidnightTimer
import org.bruce.aday.inject.ActivityContext
import org.bruce.aday.inject.ActivityScope
import org.bruce.aday.utils.addAtBottom
import org.bruce.aday.utils.addAtTop
import org.bruce.aday.utils.addBelow
import org.bruce.aday.utils.buildToolbar
import org.bruce.aday.utils.currentTheme
import org.bruce.aday.utils.dim
import org.bruce.aday.utils.dp
import org.bruce.aday.utils.setupToolbar
import org.bruce.aday.utils.sres
import javax.inject.Inject
import kotlin.math.max
import kotlin.math.min

const val MAX_CHECKMARK_COUNT = 60

@ActivityScope
class ListHabitsRootView @Inject constructor(
    @ActivityContext context: Context,
    hintListFactory: HintListFactory,
    preferences: Preferences,
    midnightTimer: MidnightTimer,
    runner: TaskRunner,
    private val listAdapter: HabitCardListAdapter,
    habitCardListViewFactory: HabitCardListViewFactory
) : FrameLayout(context), ModelObservable.Listener {

    val listView: HabitCardListView = habitCardListViewFactory.create()
    val llEmpty = EmptyListView(context)
    val tbar = buildToolbar()
    val konfettiView = KonfettiView(context).apply {
        translationZ = 10f
    }
    val progressBar = TaskProgressBar(context, runner)
    val hintView: HintView
    val header = HeaderView(context, preferences, midnightTimer)

    private var llmStatusChip: TextView? = null
    private var speechStatusChip: TextView? = null

    init {
        val hints = resources.getStringArray(R.array.hints)
        val hintList = hintListFactory.create(hints)
        hintView = HintView(context, hintList)

        val rootView = RelativeLayout(context).apply {
            background = sres.getDrawable(R.attr.windowBackgroundColor)
            addAtTop(konfettiView)
            addAtTop(tbar)
            addBelow(header, tbar)
            addBelow(listView, header, height = MATCH_PARENT)
            addBelow(llEmpty, header, height = MATCH_PARENT)
            addBelow(progressBar, header) {
                it.topMargin = dp(-6.0f).toInt()
            }
            addAtBottom(hintView)
        }
        rootView.setupToolbar(
            toolbar = tbar,
            title = resources.getString(R.string.main_activity_title),
            color = PaletteColor(17),
            displayHomeAsUpEnabled = false,
            theme = currentTheme()
        )
        installHabitsToolbarTitleRow()
        addView(rootView, MATCH_PARENT, MATCH_PARENT)
        listAdapter.setListView(listView)
    }

    override fun onModelChange() {
        updateEmptyView()
    }

    private fun setupControllers() {
        header.setScrollController(
            object : ScrollableChart.ScrollController {
                override fun onDataOffsetChanged(newDataOffset: Int) {
                    listView.dataOffset = newDataOffset
                }
            }
        )
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        setupControllers()
        listAdapter.observable.addListener(this)
    }

    override fun onDetachedFromWindow() {
        listAdapter.observable.removeListener(this)
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val count = getCheckmarkCount()
        header.buttonCount = count
        header.setMaxDataOffset(max(MAX_CHECKMARK_COUNT - count, 0))
        listView.checkmarkCount = count
        super.onSizeChanged(w, h, oldw, oldh)
    }

    private fun getCheckmarkCount(): Int {
        val nameWidth = dim(R.dimen.habitNameWidth)
        val buttonWidth = dim(R.dimen.checkmarkWidth)
        val labelWidth = max((measuredWidth / 3).toFloat(), nameWidth)
        val buttonCount = ((measuredWidth - labelWidth) / buttonWidth).toInt()
        return min(MAX_CHECKMARK_COUNT, max(0, buttonCount))
    }

    private fun installHabitsToolbarTitleRow() {
        val act = context as AppCompatActivity
        act.supportActionBar?.setDisplayShowTitleEnabled(false)
        tbar.title = ""
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleTv = TextView(context).apply {
            text = resources.getString(R.string.main_activity_title)
            setTextAppearance(context, androidx.appcompat.R.style.TextAppearance_AppCompat_Widget_ActionBar_Title)
        }
        row.addView(titleTv, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        row.addView(
            View(context).apply { layoutParams = LinearLayout.LayoutParams(dp(8f).toInt(), 1) },
        )
        val llm = TextView(context).apply {
            text = "LLM"
            applyToolbarStatusChipStyle()
        }
        val speech = TextView(context).apply {
            text = "Speech"
            applyToolbarStatusChipStyle()
        }
        llmStatusChip = llm
        speechStatusChip = speech
        row.addView(llm, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        row.addView(
            View(context).apply { layoutParams = LinearLayout.LayoutParams(dp(6f).toInt(), 1) },
        )
        row.addView(speech, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        tbar.addView(
            row,
            Toolbar.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.START or Gravity.CENTER_VERTICAL),
        )
    }

    private fun TextView.applyToolbarStatusChipStyle() {
        setTextColor(Color.WHITE)
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11f)
        val pxH = dp(5f).toInt()
        val pxW = dp(7f).toInt()
        setPadding(pxW, pxH, pxW, pxH)
    }

    /**
     * Updates the LLM / Speech chips next to the Habits title. Call from the activity on resume and
     * whenever download or model load state changes.
     */
    fun refreshModelStatusIndicators(
        llmInMemory: Boolean,
        llmDownloading: Boolean,
        llmFileOnDisk: Boolean,
        speechReady: Boolean,
        speechLoading: Boolean,
    ) {
        val llm = llmStatusChip ?: return
        val speech = speechStatusChip ?: return
        val (llmBg, llmA11y, llmLabel) = when {
            llmInMemory -> Triple(
                R.drawable.toolbar_status_chip_ready,
                R.string.toolbar_status_llm_ready,
                "LLM",
            )
            llmDownloading -> Triple(
                R.drawable.toolbar_status_chip_pending,
                R.string.toolbar_status_llm_downloading,
                "LLM",
            )
            llmFileOnDisk -> Triple(
                R.drawable.toolbar_status_chip_pending,
                R.string.toolbar_status_llm_on_disk,
                "LLM",
            )
            else -> Triple(
                R.drawable.toolbar_status_chip_off,
                R.string.toolbar_status_llm_missing,
                "LLM",
            )
        }
        llm.setBackgroundResource(llmBg)
        llm.text = llmLabel
        llm.contentDescription = resources.getString(llmA11y)
        val (spBg, spA11y, spLabel) = when {
            speechReady -> Triple(
                R.drawable.toolbar_status_chip_ready,
                R.string.toolbar_status_speech_ready,
                "Speech",
            )
            speechLoading -> Triple(
                R.drawable.toolbar_status_chip_pending,
                R.string.toolbar_status_speech_loading,
                "Speech",
            )
            else -> Triple(
                R.drawable.toolbar_status_chip_off,
                R.string.toolbar_status_speech_missing,
                "Speech",
            )
        }
        speech.setBackgroundResource(spBg)
        speech.text = spLabel
        speech.contentDescription = resources.getString(spA11y)
    }

    private fun updateEmptyView() {
        if (listAdapter.itemCount == 0) {
            if (listAdapter.hasNoHabit()) {
                llEmpty.showEmpty()
            } else {
                llEmpty.showDone()
            }
        } else {
            llEmpty.hide()
        }
    }
}
