package org.fcitx.fcitx5.android.input

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.ImageView
import android.widget.Space
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.*
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.CapabilityFlags
import org.fcitx.fcitx5.android.core.FcitxEvent
import org.fcitx.fcitx5.android.daemon.FcitxConnection
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreference
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.data.theme.ThemeManager.Prefs.NavbarBackground
import org.fcitx.fcitx5.android.input.bar.KawaiiBarComponent
import org.fcitx.fcitx5.android.input.broadcast.InputBroadcaster
import org.fcitx.fcitx5.android.input.candidates.CandidateViewBuilder
import org.fcitx.fcitx5.android.input.candidates.HorizontalCandidateComponent
import org.fcitx.fcitx5.android.input.keyboard.CommonKeyActionListener
import org.fcitx.fcitx5.android.input.keyboard.KeyboardWindow
import org.fcitx.fcitx5.android.input.picker.emojiPicker
import org.fcitx.fcitx5.android.input.picker.emoticonPicker
import org.fcitx.fcitx5.android.input.picker.symbolPicker
import org.fcitx.fcitx5.android.input.popup.PopupComponent
import org.fcitx.fcitx5.android.input.preedit.PreeditComponent
import org.fcitx.fcitx5.android.input.punctuation.PunctuationComponent
import org.fcitx.fcitx5.android.input.wm.InputWindowManager
import org.fcitx.fcitx5.android.utils.styledFloat
import org.mechdancer.dependency.DynamicScope
import org.mechdancer.dependency.manager.wrapToUniqueComponent
import org.mechdancer.dependency.plusAssign
import splitties.dimensions.dp
import splitties.views.dsl.constraintlayout.*
import splitties.views.dsl.core.*
import splitties.views.imageDrawable

@SuppressLint("ViewConstructor")
class InputView(
    val service: FcitxInputMethodService,
    val fcitx: FcitxConnection,
    val theme: Theme
) : ConstraintLayout(service) {

    private var shouldUpdateNavbarForeground = false
    private var shouldUpdateNavbarBackground = false

    private val keyBorder by ThemeManager.prefs.keyBorder
    private val navbarBackground by ThemeManager.prefs.navbarBackground

    private val customBackground = imageView {
        scaleType = ImageView.ScaleType.CENTER_CROP
    }

    private val bottomPaddingSpace = view(::Space)

    private val eventHandlerJob = fcitx.runImmediately { eventFlow }
        .onEach(::handleFcitxEvent).launchIn(service.lifecycleScope)

    private val scope = DynamicScope()
    private val themedContext = context.withTheme(R.style.Theme_InputViewTheme)
    private val broadcaster = InputBroadcaster()
    private val popup = PopupComponent()
    private val punctuation = PunctuationComponent()
    private val preedit = PreeditComponent()
    private val commonKeyActionListener = CommonKeyActionListener()
    private val candidateViewBuilder = CandidateViewBuilder()
    private val windowManager = InputWindowManager()
    private val kawaiiBar = KawaiiBarComponent()
    private val horizontalCandidate = HorizontalCandidateComponent()
    private val keyboardWindow = KeyboardWindow()
    private val symbolPicker = symbolPicker()
    private val emojiPicker = emojiPicker()
    private val emoticonPicker = emoticonPicker()

    private fun setupScope() {
        scope += this@InputView.wrapToUniqueComponent()
        scope += service.wrapToUniqueComponent()
        scope += fcitx.wrapToUniqueComponent()
        scope += theme.wrapToUniqueComponent()
        scope += themedContext.wrapToUniqueComponent()
        scope += broadcaster
        scope += popup
        scope += punctuation
        scope += preedit
        scope += commonKeyActionListener
        scope += candidateViewBuilder
        scope += windowManager
        scope += kawaiiBar
        scope += horizontalCandidate
        broadcaster.onScopeSetupFinished(scope)
    }

    private val keyboardPrefs = AppPrefs.getInstance().keyboard
    private val keyboardHeightPercent = keyboardPrefs.keyboardHeightPercent
    private val keyboardHeightPercentLandscape = keyboardPrefs.keyboardHeightPercentLandscape
    private val keyboardSidePadding = keyboardPrefs.keyboardSidePadding
    private val keyboardSidePaddingLandscape = keyboardPrefs.keyboardSidePaddingLandscape
    private val keyboardBottomPadding = keyboardPrefs.keyboardBottomPadding
    private val keyboardBottomPaddingLandscape = keyboardPrefs.keyboardBottomPaddingLandscape

    private val keyboardSizePrefs = listOf(
        keyboardHeightPercent,
        keyboardHeightPercentLandscape,
        keyboardSidePadding,
        keyboardSidePaddingLandscape,
        keyboardBottomPadding,
        keyboardBottomPaddingLandscape,
    )

    private val keyboardHeightPx: Int
        get() {
            val percent = when (resources.configuration.orientation) {
                Configuration.ORIENTATION_LANDSCAPE -> keyboardHeightPercentLandscape
                else -> keyboardHeightPercent
            }.getValue()
            return resources.displayMetrics.heightPixels * percent / 100
        }

    private val keyboardSidePaddingPx: Int
        get() {
            val value = when (resources.configuration.orientation) {
                Configuration.ORIENTATION_LANDSCAPE -> keyboardSidePaddingLandscape
                else -> keyboardSidePadding
            }.getValue()
            return dp(value)
        }

    private val keyboardBottomPaddingPx: Int
        get() {
            val value = when (resources.configuration.orientation) {
                Configuration.ORIENTATION_LANDSCAPE -> keyboardBottomPaddingLandscape
                else -> keyboardBottomPadding
            }.getValue()
            return dp(value)
        }

    private val onKeyboardSizeChangeListener = ManagedPreference.OnChangeListener<Int> { _, _ ->
        updateKeyboardSize()
    }

    val keyboardView: View

    init {
        // MUST call before any operation
        setupScope()

        keyboardSizePrefs.forEach {
            it.registerOnChangeListener(onKeyboardSizeChangeListener)
        }

        // make sure KeyboardWindow's view has been created before it receives any broadcast
        windowManager.addEssentialWindow(keyboardWindow, createView = true)
        windowManager.addEssentialWindow(symbolPicker)
        windowManager.addEssentialWindow(emojiPicker)
        windowManager.addEssentialWindow(emoticonPicker)

        broadcaster.onImeUpdate(fcitx.runImmediately { inputMethodEntryCached })

        service.window.window!!.also {
            when (navbarBackground) {
                NavbarBackground.None -> {
                    WindowCompat.setDecorFitsSystemWindows(it, true)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        it.isNavigationBarContrastEnforced = true
                    }
                }

                NavbarBackground.ColorOnly -> {
                    shouldUpdateNavbarForeground = true
                    shouldUpdateNavbarBackground = true
                    WindowCompat.setDecorFitsSystemWindows(it, true)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        it.isNavigationBarContrastEnforced = false
                    }
                }

                NavbarBackground.Full -> {
                    shouldUpdateNavbarForeground = true
                    // allow draw behind navigation bar
                    WindowCompat.setDecorFitsSystemWindows(it, false)
                    // transparent navigation bar
                    it.navigationBarColor = Color.TRANSPARENT
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        // don't apply scrim to transparent navigation bar
                        it.isNavigationBarContrastEnforced = false
                    }
                    ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
                        insets.getInsets(WindowInsetsCompat.Type.navigationBars()).let {
                            bottomPaddingSpace.updateLayoutParams<LayoutParams> {
                                height = it.bottom
                            }
                        }
                        WindowInsetsCompat.CONSUMED
                    }
                }
            }
        }

        customBackground.imageDrawable = theme.backgroundDrawable(keyBorder)

        keyboardView = constraintLayout {
            add(customBackground, lParams {
                centerVertically()
                centerHorizontally()
            })
            add(kawaiiBar.view, lParams(matchParent, dp(40)) {
                topOfParent()
                centerHorizontally()
            })
            add(windowManager.view, lParams(matchParent, keyboardHeightPx) {
                below(kawaiiBar.view)
                centerHorizontally()
                above(bottomPaddingSpace)
            })
            add(bottomPaddingSpace, lParams(matchParent) {
                centerHorizontally()
                bottomOfParent()
            })
        }

        updateKeyboardSize()

        add(preedit.ui.root, lParams(matchParent, wrapContent) {
            above(keyboardView)
            centerHorizontally()
        })
        add(keyboardView, lParams(matchParent, wrapContent) {
            centerHorizontally()
            bottomOfParent()
        })
        add(popup.root, lParams(matchParent, matchParent) {
            centerVertically()
            centerHorizontally()
        })
    }

    private fun updateKeyboardSize() {
        windowManager.view.updateLayoutParams {
            height = keyboardHeightPx
        }
        bottomPaddingSpace.updateLayoutParams<MarginLayoutParams> {
            bottomMargin = keyboardBottomPaddingPx
        }
        val sidePadding = keyboardSidePaddingPx
        kawaiiBar.view.setPadding(sidePadding, 0, sidePadding, 0)
        windowManager.view.setPadding(sidePadding, 0, sidePadding, 0)
    }

    /**
     * called when [InputView] is about to show, or restart
     */
    fun startInput(info: EditorInfo, capFlags: CapabilityFlags, restarting: Boolean = false) {
        if (!restarting) {
            if (shouldUpdateNavbarForeground || shouldUpdateNavbarBackground) {
                service.window.window!!.also {
                    if (shouldUpdateNavbarForeground) {
                        WindowCompat.getInsetsController(it, it.decorView)
                            .isAppearanceLightNavigationBars = !theme.isDark
                    }
                    if (shouldUpdateNavbarBackground) {
                        it.navigationBarColor = when (theme) {
                            is Theme.Builtin -> if (keyBorder) theme.backgroundColor else theme.keyboardColor
                            is Theme.Custom -> theme.backgroundColor
                        }
                    }
                }
            }
        }
        broadcaster.onStartInput(info, capFlags)
        windowManager.attachWindow(KeyboardWindow)
    }

    private fun handleFcitxEvent(it: FcitxEvent<*>) {
        when (it) {
            is FcitxEvent.CandidateListEvent -> {
                broadcaster.onCandidateUpdate(it.data)
            }

            is FcitxEvent.ClientPreeditEvent -> {
                broadcaster.onClientPreeditUpdate(it.data)
            }

            is FcitxEvent.InputPanelEvent -> {
                broadcaster.onInputPanelUpdate(it.data)
            }

            is FcitxEvent.IMChangeEvent -> {
                broadcaster.onImeUpdate(it.data)
            }

            is FcitxEvent.StatusAreaEvent -> {
                broadcaster.onStatusAreaUpdate(it.data)
            }

            else -> {
            }
        }
    }

    fun updateSelection(start: Int, end: Int) {
        broadcaster.onSelectionUpdate(start, end)
    }

    private var showingDialog: Dialog? = null

    fun showDialog(dialog: Dialog) {
        showingDialog?.dismiss()
        val windowToken = windowToken
        check(windowToken != null) { "InputView Token is null." }
        val window = dialog.window!!
        window.attributes.apply {
            token = windowToken
            type = WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG
        }
        window.addFlags(
            WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM or
                    WindowManager.LayoutParams.FLAG_DIM_BEHIND
        )
        window.setDimAmount(themedContext.styledFloat(android.R.attr.backgroundDimAmount))
        showingDialog = dialog.apply {
            setOnDismissListener { this@InputView.showingDialog = null }
            show()
        }
    }

    /**
     * called when [InputView] is being hidden
     */
    fun finishInput() {
        showingDialog?.dismiss()
    }

    override fun onDetachedFromWindow() {
        keyboardSizePrefs.forEach {
            it.unregisterOnChangeListener(onKeyboardSizeChangeListener)
        }
        ViewCompat.setOnApplyWindowInsetsListener(this, null)
        super.onDetachedFromWindow()
    }

    fun onDestroy() {
        showingDialog?.dismiss()
        eventHandlerJob.cancel()
        scope.clear()
    }

}
