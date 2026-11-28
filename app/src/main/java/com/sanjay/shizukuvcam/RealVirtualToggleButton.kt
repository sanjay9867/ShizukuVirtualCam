package com.sanjay.shizukuvcam

import android.content.Context
import android.util.AttributeSet
import android.view.View
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import android.content.res.ColorStateList
import android.graphics.Color

/** A prominent shortcut for switching between the device's real camera and virtual feed. */
class RealVirtualToggleButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = com.google.android.material.R.attr.materialButtonStyle
) : MaterialButton(context, attrs, defStyleAttr) {

    init {
        text = "Switch Real / Virtual Camera"
        isAllCaps = false
        setTextColor(Color.parseColor("#041F13"))
        backgroundTintList = ColorStateList.valueOf(Color.parseColor("#1DB954"))
        setOnClickListener { view ->
            val sourceSwitch = (view.rootView as? View)?.findViewById<MaterialSwitch>(R.id.switchEnableVCam)
            if (sourceSwitch != null) {
                sourceSwitch.isChecked = !sourceSwitch.isChecked
            }
        }
    }
}
