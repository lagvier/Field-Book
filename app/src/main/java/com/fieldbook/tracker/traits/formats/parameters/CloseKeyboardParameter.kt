package com.fieldbook.tracker.traits.formats.parameters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ToggleButton
import com.fieldbook.tracker.R
import com.fieldbook.tracker.database.DataHelper
import com.fieldbook.tracker.objects.TraitObject
import com.fieldbook.tracker.traits.formats.ValidationResult

class CloseKeyboardParameter(private val initialDefaultValue: Boolean? = null) :
    BaseFormatParameter(
        nameStringResourceId = R.string.traits_create_close_keyboard,
        defaultLayoutId = R.layout.list_item_trait_parameter_default_toggle_value,
        parameter = Parameters.CLOSE_KEYBOARD
    ) {

    override fun createViewHolder(
        parent: ViewGroup,
    ): BaseFormatParameter.ViewHolder {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.list_item_trait_parameter_default_toggle_value, parent, false)
        return ViewHolder(v)
    }

    inner class ViewHolder(itemView: View) : BaseFormatParameter.ViewHolder(itemView) {

        val defaultValueToggle =
            itemView.findViewById<ToggleButton>(R.id.dialog_new_trait_default_toggle_btn).also {
                initialDefaultValue?.let { value ->
                    it.isChecked = value
                }
            }

        override fun merge(traitObject: TraitObject) = traitObject.apply {
            closeKeyboardOnOpen = defaultValueToggle.isChecked
        }

        override fun load(traitObject: TraitObject?): Boolean {
            try {
                defaultValueToggle.isChecked = traitObject?.closeKeyboardOnOpen == true
            } catch (e: Exception) {
                e.printStackTrace()
                return false
            }
            return true
        }

        override fun validate(
            database: DataHelper,
            initialTraitObject: TraitObject?
        ) = ValidationResult()
    }
}