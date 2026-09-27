package io.angkyria.coreheat

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CompoundButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import io.angkyria.coreheat.heat.CoreLinkState
import io.angkyria.coreheat.heat.CoreSensorLink

class MainActivity : Activity() {

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()


    /** A dropdown over [labels], starting at [selected], reporting the position picked. */
    private fun spinner(labels: List<String>, selected: Int, onPick: (Int) -> Unit) =
        Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                labels,
            )
            setSelection(selected)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    onPick(position)
                }

                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
        }

    /**
     * Every section's content view and chevron, so opening one can close the others. Cleared at
     * the top of [onCreate]: a re-created Activity builds fresh views, and a stale entry here
     * would leave the accordion collapsing a view that is no longer on screen.
     */
    private val openSections = mutableListOf<Pair<View, TextView>>()

    /**
     * A collapsible card in Barberfish's `CollapsibleSection` shape -- white background, 1dp
     * grey border, 6dp corners, a tappable header (icon, uppercase title, description, chevron)
     * and a content area shown or hidden on tap -- rebuilt with plain views because CORE Heat has
     * no Compose dependency to draw on and must not gain one.
     *
     * Sections behave as one accordion through [openSections]: opening any card closes the rest.
     * The state is in memory only -- the Karoo does not rotate, so there is nothing to restore
     * across a re-create and no preference key is worth adding just to remember which card the
     * rider left open.
     */
    private fun section(
        title: String,
        description: String,
        iconRes: Int,
        expandedInitially: Boolean,
        vararg content: View,
    ): View {

        val icon = ImageView(this).apply {
            setImageResource(iconRes)
            // Tinted here rather than in the drawable: ic_temp is white because it is also the
            // launcher and field-picker icon, which sit on dark grounds; on this white card it
            // would vanish. Every section icon shares the brand green.
            setColorFilter(0xFF10B981.toInt())
            // Decorative: the title text right beside it already names the section.
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        val titleView = TextView(this).apply {
            text = title
            textSize = 14f
            isAllCaps = true
            setTypeface(typeface, Typeface.BOLD)
        }

        val descriptionView = TextView(this).apply {
            text = description
            textSize = 12f
        }

        // The icon sits on the title's row, and the description runs beneath BOTH of them rather
        // than being indented under the title alone. That is how Barberfish's sections read, and
        // the flush left edge is what makes a description look like the section's subtitle
        // instead of a second line of the title.
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(icon, LinearLayout.LayoutParams(dp(20), dp(20)))
            addView(titleView, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(12) })
        }

        val headerText = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(titleRow)
            addView(descriptionView, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(2) })
        }

        // Small triangles rather than a drawable: a chevron is two glyphs (open/closed) and a
        // TextView swap is simpler than a rotating ImageView for a project with no vector asset
        // for it yet.
        val chevron = TextView(this).apply {
            textSize = 16f
            text = if (expandedInitially) EXPANDED_CHEVRON else COLLAPSED_CHEVRON
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(10), dp(8), dp(10))
            isClickable = true
            addView(headerText, LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(chevron)
        }

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (expandedInitially) View.VISIBLE else View.GONE
            setPadding(dp(8), 0, dp(8), dp(8))
            content.forEach { addView(it) }
        }

        openSections += body to chevron

        header.setOnClickListener {
            // One section open at a time: close every section, then reopen this one unless it
            // was the one already open. Tapping the open section therefore closes it and leaves
            // the screen showing three headers, which is the state a rider scans from.
            val opening = body.visibility != View.VISIBLE
            openSections.forEach { (otherBody, otherChevron) ->
                otherBody.visibility = View.GONE
                otherChevron.text = COLLAPSED_CHEVRON
            }
            if (opening) {
                body.visibility = View.VISIBLE
                chevron.text = EXPANDED_CHEVRON
            }
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(6).toFloat()
                setStroke(dp(1), SECTION_BORDER_GREY)
            }
            addView(header, LinearLayout.LayoutParams(MATCH, WRAP))
            addView(body, LinearLayout.LayoutParams(MATCH, WRAP))
        }
    }

    /** The CORE card's live parts, refreshed while the screen is showing; see [refreshCore]. */
    private var coreStatus: TextView? = null
    private var corePermission: Button? = null
    private val refresher = Handler(Looper.getMainLooper())
    private val refreshTick = object : Runnable {
        override fun run() {
            refreshCore()
            refresher.postDelayed(this, CORE_REFRESH_MS)
        }
    }

    /**
     * Brings the CORE card up to date. Polled rather than observed: this Activity has no
     * coroutine scope to collect in, and the link changes state on the scale of seconds.
     */
    private fun refreshCore() {
        val granted = CoreSensorLink.hasPermission(this)
        corePermission?.visibility = if (granted) View.GONE else View.VISIBLE
        // The link only notices a new grant on its next check, so until then the permission
        // itself is the better answer to "is it still missing".
        val state = CoreSensorLink.state.value.let {
            if (it == CoreLinkState.NO_PERMISSION && granted) CoreLinkState.WAITING else it
        }
        coreStatus?.text = getString(
            when (state) {
                CoreLinkState.OFF -> R.string.core_state_off
                CoreLinkState.WAITING -> R.string.core_state_waiting
                CoreLinkState.NO_PERMISSION -> R.string.core_state_no_permission
                CoreLinkState.BLUETOOTH_OFF -> R.string.core_state_bluetooth_off
                CoreLinkState.SEARCHING -> R.string.core_state_searching
                CoreLinkState.NOT_FOUND -> R.string.core_state_not_found
                CoreLinkState.VERIFYING -> R.string.core_state_verifying
                CoreLinkState.SENSOR_HSI -> R.string.core_state_sensor_hsi
                CoreLinkState.NO_HSI -> R.string.core_state_no_hsi
            },
        )
    }

    override fun onResume() {
        super.onResume()
        refresher.post(refreshTick)
    }

    override fun onPause() {
        refresher.removeCallbacks(refreshTick)
        super.onPause()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CORE_PERMISSION_REQUEST) refreshCore()
    }

    private companion object {
        const val CORE_PERMISSION_REQUEST = 1
        const val CORE_REFRESH_MS = 1_000L

        /** Sampled off the Karoo's own app-store card, so ours sits beside it as a match. */
        const val KAROO_ALERT_YELLOW = 0xFFFFE900.toInt()

        /** A light, neutral border -- matches the weight of Barberfish's card outline. */
        const val SECTION_BORDER_GREY = 0xFFDDDDDD.toInt()
        const val EXPANDED_CHEVRON = "▾"
        const val COLLAPSED_CHEVRON = "▸"
        const val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
        const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Every view below is built fresh; anything left from a previous instance would have the
        // accordion reaching for views that are no longer on screen.
        openSections.clear()

        // Shaped like the cards the Karoo puts at the top of its own screens: a rounded yellow
        // panel with a circled mark, a short heading and the text below. The mark is an "i" and
        // the heading is not "HEADS UP", because on this device that pairing means a warning,
        // and this card only says where the fields live.
        val hintIcon = ImageView(this).apply {
            setImageResource(R.drawable.ic_info)
            setColorFilter(Color.BLACK)
            // Decorative: the heading next to it already says what this card is.
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        val hintTitle = TextView(this).apply {
            text = getString(R.string.hint_title)
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            isAllCaps = true
            letterSpacing = 0.06f
            setTextColor(Color.BLACK)
        }

        val hintHead = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(hintIcon, LinearLayout.LayoutParams(dp(20), dp(20)))
            addView(
                hintTitle,
                LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(8) },
            )
        }

        val hintBody = TextView(this).apply {
            text = getString(R.string.activity_hint)
            textSize = 13f
            setTextColor(Color.BLACK)
            setPadding(0, dp(6), 0, 0)
        }

        val hint = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(7), dp(10), dp(8))
            background = GradientDrawable().apply {
                cornerRadius = dp(6).toFloat()
                setColor(KAROO_ALERT_YELLOW)
            }
            addView(hintHead)
            addView(hintBody)
        }

        // No top padding here: this used to sit mid-list and needed a gap above it, but it is
        // now the first control in the GLOBAL section, and the section card's own header
        // already separates it from whatever is above -- the old dp(20) just doubled that gap.
        val zoneColorsLabel = TextView(this).apply {
            text = getString(R.string.setting_zone_colors)
            textSize = 18f
        }

        // Order matches ZoneColorMode so the spinner position is the ordinal.
        val modes = ZoneColorMode.entries
        val modeLabels = modes.map {
            getString(
                when (it) {
                    ZoneColorMode.OFF -> R.string.setting_zone_mode_off
                    ZoneColorMode.TEXT -> R.string.setting_zone_mode_text
                    ZoneColorMode.FILL -> R.string.setting_zone_mode_fill
                },
            )
        }
        val zoneColors = spinner(modeLabels, modes.indexOf(Settings.zoneColorMode(this))) {
            Settings.setZoneColorMode(this, modes[it])
        }

        val note = TextView(this).apply {
            text = getString(R.string.setting_zone_colors_desc)
            textSize = 13f
            setPadding(0, dp(7), 0, 0)
        }

        val raisedTail = Switch(this).apply {
            text = getString(R.string.setting_raised_tail)
            textSize = 18f
            isChecked = Settings.raisedTail(context)
            setPadding(0, dp(14), 0, 0)
            setOnCheckedChangeListener { _: CompoundButton, checked: Boolean ->
                Settings.setRaisedTail(context, checked)
            }
        }

        val raisedTailNote = TextView(this).apply {
            text = getString(R.string.setting_raised_tail_desc)
            textSize = 13f
            setPadding(0, dp(7), 0, 0)
        }

        val testMode = Switch(this).apply {
            text = getString(R.string.setting_test_mode)
            textSize = 18f
            isChecked = Settings.testMode(context)
            setPadding(0, dp(20), 0, 0)
            setOnCheckedChangeListener { _: CompoundButton, checked: Boolean ->
                Settings.setTestMode(context, checked)
            }
        }

        val testModeNote = TextView(this).apply {
            text = getString(R.string.setting_test_mode_desc)
            textSize = 13f
            setPadding(0, dp(7), 0, 0)
        }

        // Debug builds only, appended into GLOBAL below. Plausible-but-false heat readings
        // are worth keeping out of a rider's reach; this exists to shoot screenshots and
        // to look at field layout without a ride.
        val globalContent = mutableListOf(zoneColorsLabel, zoneColors, note).apply {
            if (BuildConfig.DEBUG) {
                add(testMode)
                add(testModeNote)
            }
        }

        val coreLink = Switch(this).apply {
            text = getString(R.string.setting_core_link)
            textSize = 18f
            isChecked = Settings.coreSensorLink(context)
            setOnCheckedChangeListener { _: CompoundButton, checked: Boolean ->
                Settings.setCoreSensorLink(context, checked)
            }
        }

        val coreStatusView = TextView(this).apply {
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(10), 0, 0)
        }
        coreStatus = coreStatusView

        val corePermissionButton = Button(this).apply {
            text = getString(R.string.core_permission_button)
            setOnClickListener { requestPermissions(CoreSensorLink.permissions, CORE_PERMISSION_REQUEST) }
        }
        corePermission = corePermissionButton

        val coreNote = TextView(this).apply {
            text = getString(R.string.setting_core_link_desc)
            textSize = 13f
            setPadding(0, dp(7), 0, 0)
        }

        val appearanceSection = section(
            getString(R.string.section_appearance),
            getString(R.string.section_appearance_desc),
            R.drawable.ic_appearance,
            false,
            raisedTail, raisedTailNote,
        )
        val globalSection = section(
            getString(R.string.section_global),
            getString(R.string.section_global_desc),
            R.drawable.ic_global,
            false,
            *globalContent.toTypedArray(),
        )

        // First and open on arrival: the sensor link is what this app is for, and whether it has
        // the sensor's own index is the one thing a rider opens it to check.
        val coreSection = section(
            getString(R.string.section_core),
            getString(R.string.section_core_desc),
            R.drawable.ic_temp,
            true,
            coreLink, coreStatusView, corePermissionButton, coreNote,
        )

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // 6dp around the list of cards, 8dp between them, matching Barberfish's spacing.
            setPadding(dp(6), dp(6), dp(6), dp(6))
            addView(coreSection, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(8) })
            addView(appearanceSection, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(8) })
            addView(globalSection, LinearLayout.LayoutParams(MATCH, WRAP))
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(16))
            addView(hint, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(18) })
            addView(controls)
        }

        // Scrollable: a bare LinearLayout silently clips whatever does not fit the Karoo's
        // 480x800 screen, which would put the lower controls out of reach.
        setContentView(ScrollView(this).apply { addView(content) })
    }
}
