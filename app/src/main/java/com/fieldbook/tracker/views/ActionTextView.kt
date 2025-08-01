package com.fieldbook.tracker.views

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import androidx.appcompat.widget.AppCompatTextView

/**
 * Performs an action when overriding onTouchEvent,
 * adapted from https://stackoverflow.com/questions/47107105/android-button-has-setontouchlistener-called-on-it-but-does-not-override-perform to avoid lint warnings
 */
class ActionTextView : AppCompatTextView {

    constructor(context: Context) : super(context)

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)

    override fun onTouchEvent(event: MotionEvent): Boolean {
        super.onTouchEvent(event)

        when (event.action) {
            MotionEvent.ACTION_DOWN -> return true

            MotionEvent.ACTION_UP -> {
                performClick()
                return true
            }
        }
        return false
    }

    // Because we call this from onTouchEvent, this code will be executed for both
    // normal touch events and for when the system calls this using Accessibility
    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}