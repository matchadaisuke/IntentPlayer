package com.intentplayer.ui

import android.view.View
import android.view.ViewGroup
import android.widget.NumberPicker
import android.widget.TextView

/** Applies the active Material text color to the platform NumberPicker.
 *
 * NumberPicker does not inherit Compose Material colors. On some dark themes its wheel
 * text stays dark, making both selected and adjacent values nearly invisible. Reflection
 * is used only for the optional platform setTextColor method; descendant TextViews are
 * also updated as a fallback for older implementations.
 */
internal fun applyNumberPickerTextColor(picker: NumberPicker, color: Int) {
    runCatching {
        NumberPicker::class.java
            .getMethod("setTextColor", Int::class.javaPrimitiveType)
            .invoke(picker, color)
    }
    applyTextColorRecursively(picker, color)
    picker.invalidate()
}

private fun applyTextColorRecursively(view: View, color: Int) {
    if (view is TextView) view.setTextColor(color)
    if (view is ViewGroup) {
        for (index in 0 until view.childCount) {
            applyTextColorRecursively(view.getChildAt(index), color)
        }
    }
}
