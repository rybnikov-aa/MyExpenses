package org.totschnig.myexpenses.activity

import android.annotation.TargetApi
import android.app.DownloadManager
import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.*
import android.content.SharedPreferences.OnSharedPreferenceChangeListener
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.content.res.Configuration.UI_MODE_NIGHT_NO
import android.content.res.Configuration.UI_MODE_NIGHT_YES
import android.content.res.Resources
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.annotation.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.view.ContextThemeWrapper
import androidx.appcompat.widget.Toolbar
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.os.ConfigurationCompat
import androidx.core.os.LocaleListCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.evernote.android.state.State
import com.evernote.android.state.StateSaver
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.DynamicColorsOptions
import com.google.android.material.color.HarmonizedColors
import com.google.android.material.color.HarmonizedColorsOptions
import com.google.android.material.color.MaterialColors
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.snackbar.Snackbar
import com.theartofdev.edmodo.cropper.CropImage
import com.theartofdev.edmodo.cropper.CropImageView
import com.vmadalin.easypermissions.EasyPermissions
import com.vmadalin.easypermissions.dialogs.SettingsDialog
import de.cketti.mailto.EmailIntentBuilder
import eltos.simpledialogfragment.SimpleDialog
import eltos.simpledialogfragment.SimpleDialog.OnDialogResultListener
import eltos.simpledialogfragment.form.AmountInputHostDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.totschnig.myexpenses.MyApplication
import org.totschnig.myexpenses.R
import org.totschnig.myexpenses.activity.ContribInfoDialogActivity.Companion.getIntentFor
import org.totschnig.myexpenses.dialog.*
import org.totschnig.myexpenses.dialog.ConfirmationDialogFragment.ConfirmationDialogListener
import org.totschnig.myexpenses.dialog.DialogUtils.PasswordDialogUnlockedCallback
import org.totschnig.myexpenses.feature.BankingFeature
import org.totschnig.myexpenses.feature.Feature
import org.totschnig.myexpenses.feature.FeatureManager
import org.totschnig.myexpenses.feature.Module
import org.totschnig.myexpenses.feature.values
import org.totschnig.myexpenses.injector
import org.totschnig.myexpenses.model.ContribFeature
import org.totschnig.myexpenses.myApplication
import org.totschnig.myexpenses.preference.PrefHandler
import org.totschnig.myexpenses.preference.PrefKey
import org.totschnig.myexpenses.provider.DatabaseConstants
import org.totschnig.myexpenses.provider.DatabaseConstants.KEY_COLOR
import org.totschnig.myexpenses.provider.maybeRepairRequerySchema
import org.totschnig.myexpenses.service.PlanExecutor.Companion.enqueueSelf
import org.totschnig.myexpenses.sync.GenericAccountService
import org.totschnig.myexpenses.ui.AmountInput
import org.totschnig.myexpenses.ui.SnackbarAction
import org.totschnig.myexpenses.util.*
import org.totschnig.myexpenses.util.ColorUtils.isBrightColor
import org.totschnig.myexpenses.util.PermissionHelper.PermissionGroup
import org.totschnig.myexpenses.util.TextUtils.concatResStrings
import org.totschnig.myexpenses.util.ads.AdHandlerFactory
import org.totschnig.myexpenses.util.crashreporting.CrashHandler
import org.totschnig.myexpenses.util.crashreporting.CrashHandler.Companion.report
import org.totschnig.myexpenses.util.distrib.DistributionHelper.getVersionInfo
import org.totschnig.myexpenses.util.distrib.DistributionHelper.marketSelfUri
import org.totschnig.myexpenses.util.licence.LicenceHandler
import org.totschnig.myexpenses.util.locale.HomeCurrencyProvider
import org.totschnig.myexpenses.util.tracking.Tracker
import org.totschnig.myexpenses.util.ui.UiUtils
import org.totschnig.myexpenses.util.ui.setBackgroundTintList
import org.totschnig.myexpenses.viewmodel.FeatureViewModel
import org.totschnig.myexpenses.viewmodel.OcrViewModel
import org.totschnig.myexpenses.viewmodel.ShareViewModel
import org.totschnig.myexpenses.viewmodel.data.EventObserver
import org.totschnig.myexpenses.widget.EXTRA_START_FROM_WIDGET_DATA_ENTRY
import java.io.Serializable
import java.math.BigDecimal
import java.util.*
import javax.inject.Inject
import kotlin.math.sign

abstract class BaseActivity : AppCompatActivity(), MessageDialogFragment.MessageDialogListener,
    ConfirmationDialogListener, EasyPermissions.PermissionCallbacks, AmountInput.Host, ContribIFace,
    OnDialogResultListener, OnSharedPreferenceChangeListener {
    private var snackBar: Snackbar? = null
    private var pwDialog: AlertDialog? = null

    private var _focusAfterRestoreInstanceState: Pair<Int, Int>? = null

    var scheduledRestart = false
    private var confirmCredentialResult: Boolean? = null

    lateinit var toolbar: Toolbar

    open val fabActionName: String? = null

    override fun setFocusAfterRestoreInstanceState(focusView: Pair<Int, Int>?) {
        _focusAfterRestoreInstanceState = focusView
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        _focusAfterRestoreInstanceState?.let {
            findViewById<View>(it.first)?.findViewById<View>(it.second)?.requestFocus()
        }
    }

    val floatingActionButton: FloatingActionButton
        get() = _floatingActionButton!!


    fun configureFloatingActionButton() {
        _floatingActionButton?.apply {
            fabDescription?.let { contentDescription = getString(it) }
            fabIcon?.let { setImageResource(it) }
        }
    }

    open val _floatingActionButton: FloatingActionButton? = null

    @StringRes
    open val fabDescription: Int? = null

    @DrawableRes
    open val fabIcon: Int? = null

    @JvmOverloads
    protected open fun setupToolbar(withHome: Boolean = true, homeAsUpIndicator: Int? = null) {
        toolbar = ActivityCompat.requireViewById<Toolbar>(this, R.id.toolbar).also {
            setSupportActionBar(it)
        }
        if (withHome) {
            supportActionBar?.apply {
                setDisplayHomeAsUpEnabled(true)
                homeAsUpIndicator?.let {
                    setHomeAsUpIndicator(it)
                }
            }
        }
    }

    fun setSignedToolbarColor(amount: Long) {
        val sign = amount.sign
        toolbar.setSubtitleTextColor(
            if (sign == 0) readPrimaryTextColor(this) else
                ResourcesCompat.getColor(
                    resources,
                    if (sign == -1) R.color.colorExpense else R.color.colorIncome,
                    null
                )
        )
    }

    fun enqueuePlanner(forceImmediate: Boolean) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                enqueueSelf(this@BaseActivity, prefHandler, forceImmediate)
            }
        }
    }

    override fun onPermissionsGranted(requestCode: Int, perms: List<String>) {
        if (requestCode == PermissionHelper.PERMISSIONS_REQUEST_WRITE_CALENDAR) {
            enqueuePlanner(true)
        }
    }

    override fun showCalculator(amount: BigDecimal?, id: Int) {
        val intent = Intent(this, CalculatorInput::class.java).apply {
            forwardDataEntryFromWidget(this)
            if (amount != null) {
                putExtra(DatabaseConstants.KEY_AMOUNT, amount)
            }
            putExtra(CalculatorInput.EXTRA_KEY_INPUT_ID, id)
            putExtra(KEY_COLOR, color)
        }
        (supportFragmentManager.findFragmentById(0) as? AmountInputHostDialog)?.also {
            it.startActivityForResult(intent, CALCULATOR_REQUEST)
        } ?: kotlin.run { startActivityForResult(intent, CALCULATOR_REQUEST) }
    }

    protected open fun forwardDataEntryFromWidget(intent: Intent) {
        intent.putExtra(
            EXTRA_START_FROM_WIDGET_DATA_ENTRY,
            getIntent().getBooleanExtra(EXTRA_START_FROM_WIDGET_DATA_ENTRY, false)
        )
    }

    private val downloadReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            onDownloadComplete()
        }
    }

    val progressDialogFragment: ProgressDialogFragment?
        get() = (supportFragmentManager.findFragmentByTag(PROGRESS_TAG) as? ProgressDialogFragment)

    fun copyToClipboard(text: String) {
        showSnackBar(
            try {
                ContextCompat.getSystemService(this, ClipboardManager::class.java)
                    ?.setPrimaryClip(ClipData.newPlainText(null, text))
                "${getString(R.string.toast_text_copied)}: $text"
            } catch (e: RuntimeException) {
                report(e)
                e.safeMessage
            }
        )
    }

    fun sendEmail(
        recipient: String,
        subject: String,
        body: String
    ) {
        if (!EmailIntentBuilder.from(this)
                .to(recipient)
                .subject(subject)
                .body(body)
                .start()
        ) {
            showMessage(body)
        }
    }

    fun startActivity(intent: Intent, notAvailableMessage: Int, forResultRequestCode: Int? = null) {
        try {
            if (forResultRequestCode != null)
                startActivityForResult(intent, forResultRequestCode)
            else
                startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            showSnackBar(notAvailableMessage)
        }
    }

    override fun contribFeatureCalled(feature: ContribFeature, tag: Serializable?) {
        if (feature == ContribFeature.BANKING) {
            if (featureViewModel.isFeatureAvailable(this, Feature.FINTS)) {
                startBanking()
            } else {
                featureViewModel.requestFeature(this, Feature.FINTS)
            }
        }

    }

    private fun onDownloadComplete() {
        downloadPending?.let {
            showSnackBar(getString(R.string.download_completed, it))
        }
        downloadPending = null
    }

    @State
    var downloadPending: String? = null

    @State
    var color = 0

    @Inject
    lateinit var prefHandler: PrefHandler

    val collate: String
        get() = prefHandler.collate

    @Inject
    lateinit var tracker: Tracker

    @Inject
    lateinit var crashHandler: CrashHandler

    @Inject
    lateinit var licenceHandler: LicenceHandler

    @Inject
    lateinit var featureManager: FeatureManager

    @Inject
    lateinit var adHandlerFactory: AdHandlerFactory

    @Inject
    lateinit var homeCurrencyProvider: HomeCurrencyProvider

    val homeCurrency by lazy {
        homeCurrencyProvider.homeCurrencyUnit
    }

    val ocrViewModel: OcrViewModel by viewModels()
    val featureViewModel: FeatureViewModel by viewModels()
    val shareViewModel: ShareViewModel by viewModels()

    private var helpVariant: String? = null

    override fun attachBaseContext(newBase: Context?) {
        super.attachBaseContext(newBase)
        injectDependencies()
    }

    protected open fun injectDependencies() {
        injector.inject(this)
    }

    @CallSuper
    open fun onFeatureAvailable(feature: Feature) {
        featureManager.initActivity(this)
        if (feature == Feature.FINTS) startBanking()
    }

    open fun maybeRepairRequerySchema() {
        maybeRepairRequerySchema(prefHandler)
    }

    fun harmonizeColors() {
        HarmonizedColors.applyToContextIfAvailable(
            this,
            HarmonizedColorsOptions.Builder()
                .setColorResourceIds(
                    intArrayOf(
                        R.color.colorExpenseLight,
                        R.color.colorIncomeLight,
                        R.color.colorExpenseDark,
                        R.color.colorIncomeDark,
                        R.color.UNRECONCILED,
                        R.color.CLEARED,
                        R.color.RECONCILED,
                        R.color.VOID
                    )
                )
                .build()
        )
    }

    private val contentColor: Int
        get() = if (canUseContentColor)
            color.takeIf { it != 0 } ?: intent.getIntExtra(KEY_COLOR, 0)
        else 0

    override fun onCreate(savedInstanceState: Bundle?) {
        with(injector) {
            inject(ocrViewModel)
            inject(featureViewModel)
            inject(shareViewModel)
        }

        StateSaver.restoreInstanceState(this, savedInstanceState)
        contentColor.takeIf { it != 0 }?.also {
            DynamicColors.applyToActivityIfAvailable(
                this,
                DynamicColorsOptions.Builder()
                    .setContentBasedSource(it)
                    .setOnAppliedCallback {
                        harmonizeColors()
                    }
                    .build()
            )
        } ?: run { harmonizeColors() }

        featureViewModel.getFeatureState().observe(this, EventObserver { featureState ->
            when (featureState) {
                is FeatureViewModel.FeatureState.FeatureLoading -> showSnackBar(
                    getString(
                        R.string.feature_download_requested,
                        getString(featureState.feature.labelResId)
                    )
                )

                is FeatureViewModel.FeatureState.FeatureAvailable -> {
                    showSnackBar(
                        featureState.modules.map { Module.print(this, it) }
                            .joinToString(" ") {
                                getString(R.string.feature_downloaded, it)
                            }
                    )
                    Feature.values.find { featureState.modules.contains(it.mainModule.moduleName) }?.also {
                        //after the dynamic feature module has been installed, we need to check if data needed by the module (e.g. Tesseract) has been downloaded
                        if (!featureViewModel.isFeatureAvailable(this, it)) {
                            featureViewModel.requestFeature(this, it)
                        } else {
                            onFeatureAvailable(it)
                        }
                    } ?: run { report(Throwable("No feature found for ${featureState.modules.joinToString()}")) }

                }

                is FeatureViewModel.FeatureState.Error -> {
                    with(featureState.throwable) {
                        report(this)
                        message?.let { showSnackBar(it) }
                    }
                }

                is FeatureViewModel.FeatureState.LanguageLoading -> showSnackBar(
                    getString(
                        R.string.language_download_requested,
                        featureState.language
                    )
                )

                is FeatureViewModel.FeatureState.LanguageAvailable -> {
                    setLanguage(featureState.language)
                    recreate()
                }
            }
        })
        super.onCreate(savedInstanceState)
        tracker.init(this, licenceHandler.licenceStatus)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                shareViewModel.shareResult.collect { result ->
                    val callback = object : Snackbar.Callback() {
                        override fun onDismissed(transientBottomBar: Snackbar?, event: Int) {
                            if (event == DISMISS_EVENT_SWIPE || event == DISMISS_EVENT_ACTION) {
                                shareViewModel.messageShown()
                            }
                        }
                    }
                    result?.onFailure {
                        showDismissibleSnackBar(it.safeMessage, callback)
                    }?.onSuccess {
                        if (it == ShareViewModel.Scheme.HTTP || it == ShareViewModel.Scheme.HTTPS) {
                            showDismissibleSnackBar("HTTP PUT completed successfully.", callback)
                        }
                    }
                }
            }
        }
        if (prefHandler.shouldSecureWindow) {
            window.setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE
            )
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        StateSaver.saveInstanceState(this, outState)
    }

    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        configureFloatingActionButton()
        _floatingActionButton?.let {
            it.setOnClickListener {
                onFabClicked()
            }
        }
    }

    @CallSuper
    open fun onFabClicked() {
        fabActionName?.let { trackCommand(it) }
    }

    fun setLanguage(language: String) {
        AppCompatDelegate.setApplicationLocales(
            if (language == MyApplication.DEFAULT_LANGUAGE)
                LocaleListCompat.getEmptyLocaleList() else
                LocaleListCompat.forLanguageTags(
                    language
                )
        )
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == CONFIRM_DEVICE_CREDENTIALS_UNLOCK_REQUEST) {
            if (resultCode == RESULT_OK) {
                confirmCredentialResult = true
                showWindow()
                requireApplication().isLocked = false
            } else {
                confirmCredentialResult = false
            }
        }
    }

    open fun requireApplication() = application as MyApplication

    override fun onResume() {
        super.onResume()
        ContextCompat.registerReceiver(
            this,
            downloadReceiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            ContextCompat.RECEIVER_EXPORTED
        )
        featureViewModel.registerCallback()
        if (scheduledRestart) {
            scheduledRestart = false
            recreate()
        } else {
            confirmCredentialResult?.also {
                if (!it) {
                    moveTaskToBack(true)
                }
                confirmCredentialResult = null
            } ?: run {
                if (requireApplication().shouldLock(this)) {
                    confirmCredentials(CONFIRM_DEVICE_CREDENTIALS_UNLOCK_REQUEST, null, true)
                }
            }
        }
    }

    override fun onResult(dialogTag: String, which: Int, extras: Bundle) =
        if (dialogTag == DIALOG_INACTIVE_BACKEND && which == OnDialogResultListener.BUTTON_POSITIVE) {
            GenericAccountService.activateSync(
                extras.getString(DatabaseConstants.KEY_SYNC_ACCOUNT_NAME)!!,
                prefHandler
            )
            true
        } else false

    open fun hideWindow() {
        findViewById<View>(android.R.id.content).visibility = View.GONE
        supportActionBar?.hide()
    }

    open fun showWindow() {
        findViewById<View>(android.R.id.content).visibility = View.VISIBLE
        supportActionBar?.show()
    }

    protected open fun confirmCredentials(
        requestCode: Int,
        legacyUnlockCallback: PasswordDialogUnlockedCallback?,
        shouldHideWindow: Boolean
    ) {
        if (prefHandler.getBoolean(PrefKey.PROTECTION_DEVICE_LOCK_SCREEN, false)) {
            val intent = (getSystemService(KEYGUARD_SERVICE) as KeyguardManager)
                .createConfirmDeviceCredentialIntent(null, null)
            if (intent != null) {
                if (shouldHideWindow) hideWindow()
                try {
                    startActivityForResult(intent, requestCode)
                    requireApplication().isLocked = true
                } catch (e: ActivityNotFoundException) {
                    showSnackBar("No activity found for confirming device credentials")
                }
            } else {
                showDeviceLockScreenWarning()
                legacyUnlockCallback?.onPasswordDialogUnlocked()
            }
        } else if (prefHandler.getBoolean(PrefKey.PROTECTION_LEGACY, true)) {
            if (shouldHideWindow) hideWindow()
            if (pwDialog == null) {
                pwDialog = DialogUtils.passwordDialog(this, false)
            }
            DialogUtils.showPasswordDialog(this, pwDialog, legacyUnlockCallback)
            requireApplication().isLocked = true
        }
    }

    open fun showDeviceLockScreenWarning() {
        showSnackBar(
            concatResStrings(
                this,
                " ",
                R.string.warning_device_lock_screen_not_set_up_1,
                R.string.warning_device_lock_screen_not_set_up_2
            )
        )
    }

    override fun onSharedPreferenceChanged(
        sharedPreferences: SharedPreferences,
        key: String?
    ) {
        if (key != null && prefHandler.matches(
                key,
                PrefKey.CUSTOM_DATE_FORMAT,
                PrefKey.DB_SAFE_MODE,
                PrefKey.GROUP_MONTH_STARTS,
                PrefKey.GROUP_WEEK_STARTS,
                PrefKey.HOME_CURRENCY,
                PrefKey.PROTECTION_ALLOW_SCREENSHOT,
                PrefKey.PROTECTION_DEVICE_LOCK_SCREEN,
                PrefKey.PROTECTION_LEGACY,
                PrefKey.UI_FONTSIZE,
                PrefKey.CUSTOMIZE_MAIN_MENU
            )
        ) {
            scheduledRestart = true
        }
    }

    override fun onPause() {
        super.onPause()
        val app = requireApplication()
        if (app.isLocked && pwDialog != null) {
            pwDialog!!.dismiss()
        } else {
            app.setLastPause(this)
        }
        try {
            unregisterReceiver(downloadReceiver)
        } catch (e: IllegalArgumentException) {
            //Mainly hits Android 4, 5 and 6, no need to report
            //CrashHandler.report(e)
        }
        featureViewModel.unregisterCallback()
    }

    fun logEvent(event: String, params: Bundle?) {
        tracker.logEvent(event, params)
    }

    fun trackCommand(command: Int, postFix: String = "") {
        try {
            resources.getResourceName(command)
        } catch (e: Resources.NotFoundException) {
            null
        }?.let { fullResourceName ->
            trackCommand(fullResourceName.substring(fullResourceName.indexOf('/') + 1) + postFix)
        }
    }

    fun trackCommand(command: String) {
        tracker.trackCommand(command)
    }

    @CallSuper
    override fun onPositive(args: Bundle, checked: Boolean) {
        val command = args.getInt(ConfirmationDialogFragment.KEY_COMMAND_POSITIVE)
        trackCommand(command, if (checked) "_CHECKED" else "")
        dispatchCommand(
            command,
            args.getSerializable(ConfirmationDialogFragment.KEY_TAG_POSITIVE)
        )
    }

    @CallSuper
    override fun dispatchCommand(command: Int, tag: Any?): Boolean {
        trackCommand(command)
        return when (command) {
            R.id.TESSERACT_DOWNLOAD_COMMAND -> {
                ocrViewModel.downloadTessData().observe(this) {
                    downloadPending = it
                }
                true
            }

            R.id.QUIT_COMMAND -> {
                finish()
                true
            }

            R.id.NOTIFICATION_SETTINGS_COMMAND -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    !PermissionGroup.NOTIFICATION.hasPermission(this)
                ) {
                    disableFab()
                    requestPermission(
                        PermissionHelper.PERMISSIONS_REQUEST_NOTIFICATIONS_PLANNER,
                        PermissionGroup.NOTIFICATION
                    )
                } else {

                    val intent = Intent().apply {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            val channelId = if (
                                NotificationManagerCompat.from(this@BaseActivity)
                                    .areNotificationsEnabled()
                            ) NotificationBuilderWrapper.CHANNEL_ID_PLANNER else null
                            action = when (channelId) {
                                null -> Settings.ACTION_APP_NOTIFICATION_SETTINGS
                                else -> Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS
                            }
                            channelId?.let { putExtra(Settings.EXTRA_CHANNEL_ID, it) }
                            putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                        } else {
                            action = "android.settings.APP_NOTIFICATION_SETTINGS"
                            putExtra("app_package", packageName)
                            putExtra("app_uid", applicationInfo.uid)
                        }
                    }
                    startActivity(intent)
                }
                true
            }

            R.id.RATE_COMMAND -> {
                startActivity(Intent(Intent.ACTION_VIEW).apply {
                    data = Uri.parse(marketSelfUri)
                }, R.string.error_accessing_market, null)
                true
            }

            R.id.SETTINGS_COMMAND -> {
                withRestoreOk.launch(PreferenceActivity.getIntent(this))
                true
            }

            R.id.FEEDBACK_COMMAND -> {
                val licenceStatus = licenceHandler.licenceStatus
                val licenceInfo = buildString {
                    if (licenceStatus != null) {
                        append(licenceStatus.name)
                    }
                    licenceHandler.purchaseExtraInfo.takeIf { !TextUtils.isEmpty(it) }?.let {
                        append(" ($it)")
                    }
                }.takeIf { it.isNotEmpty() }?.let {
                    "LICENCE: $it\n"
                }
                val firstInstallVersion = prefHandler.getInt(PrefKey.FIRST_INSTALL_VERSION, 0)
                val firstInstallSchema =
                    prefHandler.getInt(PrefKey.FIRST_INSTALL_DB_SCHEMA_VERSION, -1)

                sendEmail(
                    recipient = getString(R.string.support_email),
                    subject = "[" + getString(R.string.app_name) + "] " + getString(R.string.feedback),
                    body = """
                        APP_VERSION:${getVersionInfo(this)}
                        FIRST_INSTALL_VERSION:$firstInstallVersion (DB_SCHEMA $firstInstallSchema)
                        ANDROID_VERSION:${Build.VERSION.RELEASE}
                        BRAND:${Build.BRAND}
                        MODEL:${Build.MODEL}
                        CONFIGURATION:${ConfigurationHelper.configToJson(resources.configuration)}
                        $licenceInfo

                    """.trimIndent()
                )
                true
            }

            R.id.CONTRIB_INFO_COMMAND -> {
                showContribDialog(null, null)
                true
            }

            R.id.WEB_COMMAND -> {
                startActionView(getString(R.string.website))
                true
            }

            R.id.HELP_COMMAND -> doHelp(tag as String?)

            android.R.id.home -> {
                doHome()
                true
            }

            else -> false
        }
    }

    protected open fun doHome() {
        setResult(RESULT_CANCELED)
        finish()
    }

    fun processImageCaptureError(resultCode: Int, activityResult: CropImage.ActivityResult?) {
        if (resultCode == CropImage.CROP_IMAGE_ACTIVITY_RESULT_ERROR_CODE) {
            val throwable = activityResult?.error ?: Throwable("ERROR")
            report(throwable)
            showSnackBar(if (throwable is ActivityNotFoundException) getString(R.string.image_capture_not_installed) else throwable.safeMessage)
        }
    }

    @JvmOverloads
    fun showDismissibleSnackBar(message: Int, callback: Snackbar.Callback? = null) {
        showDismissibleSnackBar(getText(message), callback)
    }

    @JvmOverloads
    fun showDismissibleSnackBar(
        message: CharSequence,
        callback: Snackbar.Callback? = null,
        actionLabel: String = getString(R.string.dialog_dismiss)
    ) {
        showSnackBar(
            message, Snackbar.LENGTH_INDEFINITE,
            SnackbarAction(actionLabel) { snackBar?.dismiss() }, callback
        )
    }

    fun showSnackBarIndefinite(message: Int) {
        showSnackBar(message, Snackbar.LENGTH_INDEFINITE)
    }

    @JvmOverloads
    fun showSnackBar(message: Int, duration: Int = Snackbar.LENGTH_LONG) {
        showSnackBar(getText(message), duration)
    }

    @JvmOverloads
    fun showSnackBar(
        message: CharSequence,
        duration: Int = Snackbar.LENGTH_LONG,
        snackBarAction: SnackbarAction? = null,
        callback: Snackbar.Callback? = null
    ) {
        snackBarContainer?.let {
            showSnackBar(message, duration, snackBarAction, callback, it)
        } ?: showSnackBarFallBack(message)
    }

    private fun showSnackBarFallBack(message: CharSequence) {
        reportMissingSnackBarContainer()
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    open fun reportMissingSnackBarContainer() {
        report(Exception("Class $javaClass is unable to display snackBar"))
    }

    private val snackBarContainer: View?
        get() = findViewById(snackBarContainerId) ?: findViewById(android.R.id.content)

    fun showProgressSnackBar(
        message: CharSequence,
        total: Int = 0,
        progress: Int = 0,
        container: View? = null
    ) {
        (container ?: snackBarContainer)?.also {
            val displayMessage = if (total > 0) "$message ($progress/$total)" else message
            if (snackBar == null) {
                snackBar = Snackbar.make(it, displayMessage, Snackbar.LENGTH_INDEFINITE).apply {
                    (view.findViewById<View>(com.google.android.material.R.id.snackbar_text).parent as ViewGroup)
                        .addView(
                            ProgressBar(
                                ContextThemeWrapper(
                                    this@BaseActivity,
                                    R.style.SnackBarTheme
                                )
                            ).apply {
                                layoutParams = LinearLayout.LayoutParams(
                                    LinearLayout.LayoutParams.WRAP_CONTENT,
                                    LinearLayout.LayoutParams.WRAP_CONTENT
                                ).apply {
                                    gravity = Gravity.CENTER_VERTICAL
                                }
                            }
                        )
                    addCallback(object : Snackbar.Callback() {
                        override fun onDismissed(transientBottomBar: Snackbar?, event: Int) {
                            snackBar = null
                        }
                    })
                    show()
                }
            } else {
                snackBar?.setText(displayMessage)
            }
        } ?: showSnackBarFallBack(message)
    }

    fun updateDismissibleSnackBar(message: CharSequence) {
        snackBar?.setText(message) ?: run {
            showDismissibleSnackBar(message)
        }
    }

    fun showSnackBar(
        message: CharSequence, duration: Int, snackBarAction: SnackbarAction?,
        callback: Snackbar.Callback?, container: View
    ) {
        snackBar = Snackbar.make(container, message, duration).apply {
            UiUtils.increaseSnackbarMaxLines(this)
            if (snackBarAction != null) {
                setAction(snackBarAction.label, snackBarAction.listener)
            }
            if (callback != null) {
                addCallback(callback)
            }
            addCallback(object : Snackbar.Callback() {
                override fun onDismissed(transientBottomBar: Snackbar?, event: Int) {
                    snackBar = null
                }
            })
            show()
        }

    }

    fun dismissSnackBar() {
        snackBar?.dismiss()
        snackBar = null
    }

    @IdRes
    protected open val snackBarContainerId: Int = R.id.fragment_container

    private fun offerTessDataDownload() {
        ocrViewModel.offerTessDataDownload(this)
    }

    fun checkTessDataDownload() {
        ocrViewModel.tessDataExists().observe(this) {
            if (!it)
                offerTessDataDownload()
        }
    }

    fun startActionView(uri: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse(uri)
            })
        } catch (e: ActivityNotFoundException) {
            showSnackBar("No activity found for opening $uri")
        }
    }

    @JvmOverloads
    open fun showMessage(
        message: CharSequence,
        positive: MessageDialogFragment.Button? = MessageDialogFragment.okButton(),
        neutral: MessageDialogFragment.Button? = null,
        negative: MessageDialogFragment.Button? = null,
        cancellable: Boolean = true
    ) {
        lifecycleScope.launchWhenResumed {
            MessageDialogFragment.newInstance(null, message, positive, neutral, negative).apply {
                isCancelable = cancellable
            }.show(supportFragmentManager, "MESSAGE")
        }
    }

    fun showVersionDialog(prev_version: Int) {
        lifecycleScope.launchWhenResumed {
            VersionDialogFragment.newInstance(prev_version)
                .show(supportFragmentManager, "VERSION_INFO")
        }
    }

    val unencryptedBackupWarning
        get() = getString(
            R.string.warning_unencrypted_backup,
            getString(R.string.pref_security_export_passphrase_title)
        )

    fun getLocale(): Locale =
        ConfigurationCompat.getLocales(resources.configuration).get(0) ?: Locale.getDefault()

    override fun onMessageDialogDismissOrCancel() {}

    fun initLocaleContext() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            requireApplication().setUserPreferredLocale(AppCompatDelegate.getApplicationLocales()[0])
        }
        DatabaseConstants.buildLocalized(
            getLocale(),
            this,
            prefHandler,
            homeCurrencyProvider.homeCurrencyString
        )
    }

    fun deleteFailureMessage(message: String?) =
        "There was an error deleting the object${message?.let { " ($it)" } ?: ""}. Please contact support@myexenses.mobi !"

    fun showDeleteFailureFeedback(message: String? = null, callback: Snackbar.Callback? = null) {
        showDismissibleSnackBar(deleteFailureMessage(message), callback)
    }

    protected open fun doHelp(variant: String?): Boolean {
        startActivity(Intent(this, Help::class.java).apply {
            putExtra(HelpDialogFragment.KEY_CONTEXT, helpContext)
            putExtra(HelpDialogFragment.KEY_VARIANT, variant ?: helpVariant)
        })
        return true
    }

    protected open val helpContext: String
        get() = javaClass.simpleName

    fun setHelpVariant(helpVariant: String, addBreadCrumb: Boolean = false) {
        this.helpVariant = helpVariant
        if (addBreadCrumb) {
            crashHandler.addBreadcrumb(helpVariant)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        _floatingActionButton?.let {
            it.isEnabled = true
        }
        EasyPermissions.onRequestPermissionsResult(requestCode, permissions, grantResults, this)
    }

    open fun contribFeatureRequested(feature: ContribFeature, tag: Serializable? = null) {
        if (licenceHandler.hasAccessTo(feature)) {
            (this as ContribIFace).contribFeatureCalled(feature, tag)
        } else {
            showContribDialog(feature, tag)
        }
    }

    open fun showContribDialog(feature: ContribFeature? = null, tag: Serializable? = null) {
        startActivityForResult(getIntentFor(this, feature).apply {
            putExtra(ContribInfoDialogActivity.KEY_TAG, tag)
        }, CONTRIB_REQUEST)
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    fun requestNotificationPermission(requestCode: Int) {
        requestPermission(
            requestCode,
            PermissionGroup.NOTIFICATION
        )
    }

    fun checkNotificationPermissionForAutoBackup() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !PermissionGroup.NOTIFICATION.hasPermission(this)
        ) {
            requestNotificationPermission(PermissionHelper.PERMISSIONS_REQUEST_NOTIFICATIONS_AUTO_BACKUP)
        } else if (!areNotificationsEnabled(NotificationBuilderWrapper.CHANNEL_ID_AUTO_BACKUP)) {
            showSnackBar(
                TextUtils.concat(
                    getString(R.string.notifications_permission_required_auto_backup),
                    " ",
                    getString(
                        R.string.notifications_channel_required,
                        getString(R.string.pref_auto_backup_title)
                    )
                )
            )
        }
    }

    fun checkPermissionsForPlaner() {

        val missingPermissions = buildList {
            add(PermissionGroup.CALENDAR)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(PermissionGroup.NOTIFICATION)
            }
        }.filter { !it.hasPermission(this) }

        if (missingPermissions.contains(PermissionGroup.CALENDAR)) {
            disableFab()
            requestPermission(
                PermissionHelper.PERMISSIONS_REQUEST_WRITE_CALENDAR,
                *missingPermissions.toTypedArray()
            )
        } else {

            val prefKey = "notification_permission_rationale_shown"
            if (!areNotificationsEnabled(NotificationBuilderWrapper.CHANNEL_ID_PLANNER) &&
                !prefHandler.getBoolean(prefKey, false)
            ) {
                ConfirmationDialogFragment.newInstance(
                    Bundle().apply {
                        putString(ConfirmationDialogFragment.KEY_PREFKEY, prefKey)
                        putCharSequence(
                            ConfirmationDialogFragment.KEY_MESSAGE,
                            TextUtils.concat(
                                Utils.getTextWithAppName(
                                    this@BaseActivity,
                                    R.string.notifications_permission_required_planner
                                ),
                                " ",
                                getString(
                                    R.string.notifications_channel_required,
                                    getString(R.string.planner_notification_channel_name)
                                )
                            )
                        )
                        putInt(
                            ConfirmationDialogFragment.KEY_COMMAND_POSITIVE,
                            R.id.NOTIFICATION_SETTINGS_COMMAND
                        )
                        putInt(
                            ConfirmationDialogFragment.KEY_POSITIVE_BUTTON_LABEL,
                            R.string.menu_reconfigure
                        )
                    }
                ).show(supportFragmentManager, "NOTIFICATION_PERMISSION_RATIONALE")
            }
        }
    }

    private fun areNotificationsEnabled(channelId: String) =
        if (NotificationManagerCompat.from(this).areNotificationsEnabled()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                val channel = manager.getNotificationChannel(channelId)
                channel?.importance != NotificationManager.IMPORTANCE_NONE
            } else {
                true
            }
        } else false

    fun requestCalendarPermission() {
        disableFab()
        requestPermission(
            PermissionHelper.PERMISSIONS_REQUEST_WRITE_CALENDAR,
            PermissionGroup.CALENDAR
        )
    }

    private fun disableFab() {
        _floatingActionButton?.let {
            it.isEnabled = false
        }
    }

    open fun requestPermission(requestCode: Int, vararg permissionGroup: PermissionGroup) {
        EasyPermissions.requestPermissions(
            host = this,
            rationale = PermissionHelper.getRationale(this, requestCode, *permissionGroup),
            requestCode = requestCode,
            perms = permissionGroup.flatMap { it.androidPermissions }.toTypedArray()
        )
    }

    @TargetApi(Build.VERSION_CODES.TIRAMISU)
    override fun onPermissionsDenied(requestCode: Int, perms: List<String>) {
        if (EasyPermissions.somePermissionPermanentlyDenied(this, perms)) {
            SettingsDialog.Builder(this)
                .title(R.string.permissions_label)
                .rationale(
                    PermissionHelper.getRationale(
                        this, requestCode,
                        *perms.map { PermissionGroup.fromPermission(it) }.distinct().toTypedArray()
                    )
                )
                .build().show()
        } else if (requestCode == PermissionHelper.PERMISSIONS_REQUEST_NOTIFICATIONS_AUTO_BACKUP) {
            showSnackBar(
                PermissionHelper.getRationale(
                    this, requestCode, PermissionHelper.PermissionGroup.NOTIFICATION
                )
            )
        }
    }

    fun showDetails(transactionId: Long) {
        lifecycleScope.launchWhenResumed {
            TransactionDetailFragment.show(transactionId, supportFragmentManager)
        }
    }

    fun requireFeature(feature: Feature) {
        featureViewModel.requireFeature(this, feature)
    }

    fun isFeatureAvailable(feature: Feature) = featureViewModel.isFeatureAvailable(this, feature)

    override fun onNeutral(args: Bundle) {}
    override fun onNegative(args: Bundle) {}
    override fun onDismissOrCancel() {}

    fun hideKeyboard() {
        val im = applicationContext.getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        im.hideSoftInputFromWindow(window.decorView.windowToken, InputMethodManager.HIDE_NOT_ALWAYS)
    }

    fun startMediaChooserDo(fileName: String) {
        lifecycleScope.launch {
            val uris = withContext(Dispatchers.IO) {
                PictureDirHelper.getOutputMediaUri(
                    temp = true,
                    application = myApplication,
                    fileName = fileName
                ) to PictureDirHelper.getOutputMediaUri(
                    temp = true,
                    application = myApplication,
                    fileName = "${fileName}_CROPPED"
                )
            }
            CropImage.activity()
                .setCameraOnly(!prefHandler.getBoolean(PrefKey.CAMERA_CHOOSER, false))
                .setCameraPackage(
                    prefHandler.getString(PrefKey.CAMERA_APP)?.takeIf { it.isNotEmpty() })
                .setAllowFlipping(false)
                .setCaptureImageOutputUri(uris.first)
                .setOutputUri(uris.second)
                .setGuidelines(CropImageView.Guidelines.ON)
                .start(this@BaseActivity)
        }
    }

    fun maybeApplyDynamicColor(): Boolean = if (canUseContentColor) {
        recreate()
        true
    } else false

    val canUseContentColor: Boolean by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (prefHandler.getInt(PrefKey.UI_FONTSIZE, 0) == 0) true else {
                val uiModeFromPref = prefHandler.uiMode(this)
                if (uiModeFromPref == "default") true else {
                    val ourUiMode = if (uiModeFromPref == "dark")
                        UI_MODE_NIGHT_YES else UI_MODE_NIGHT_NO
                    val systemUiMode =
                        applicationContext.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
                    ourUiMode == systemUiMode
                }
            }
        } else false
    }

    fun tintSystemUiAndFab(color: Int) {
        //If we use dynamic content based color, we do not need to harmonize the color
        val harmonized =
            if (canUseContentColor) color else MaterialColors.harmonizeWithPrimary(this, color)
        tintSystemUi(harmonized)
        floatingActionButton.setBackgroundTintList(harmonized)
    }

    fun tintSystemUi(color: Int) {

        if (shouldTintSystemUi()) {
            with(window) {
                clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS)
                addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
                statusBarColor = color
                navigationBarColor = color
            }
            with(WindowInsetsControllerCompat(window, window.decorView)) {
                val isBright = isBrightColor(color)
                isAppearanceLightNavigationBars = isBright
                isAppearanceLightStatusBars = isBright
            }
        }
    }

    private fun shouldTintSystemUi() = try {
        //on DialogWhenLargeTheme we do not want to tint if we are displayed on a large screen as dialog
        packageManager.getActivityInfo(componentName, 0).themeResource != R.style.EditDialog ||
                resources.configuration.screenLayout and Configuration.SCREENLAYOUT_SIZE_MASK <
                Configuration.SCREENLAYOUT_SIZE_LARGE
    } catch (e: PackageManager.NameNotFoundException) {
        report(e)
        false
    }

    val withRestoreOk =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (it.resultCode == ProtectedFragmentActivity.RESULT_RESTORE_OK) {
                restartAfterRestore()
            }
        }
    protected val calledFromOnboarding: Boolean
        get() = callingActivity?.let {
            Utils.getSimpleClassNameFromComponentName(it)
        } == OnboardingActivity::class.java.simpleName
    val bankingFeature: BankingFeature
        get() = requireApplication().appComponent.bankingFeature() ?: object :
            BankingFeature {}

    protected open fun restartAfterRestore() {
        (application as MyApplication).invalidateHomeCurrency(homeCurrencyProvider.homeCurrencyString)
        if (!isFinishing) {
            finishAffinity()
            startActivity(Intent(this, MyExpenses::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            })
        }
    }

    open fun startBanking() {
        startActivity(Intent(this, bankingFeature.bankingActivityClass))
    }

    fun requestSync(accountName: String, uuid: String? = null) {
        if (!GenericAccountService.requestSync(accountName, uuid = uuid)) {
            val bundle = Bundle(1)
            bundle.putString(DatabaseConstants.KEY_SYNC_ACCOUNT_NAME, accountName)
            SimpleDialog.build()
                .msg("Backend is not ready to be synced")
                .pos("Activate again")
                .extra(bundle)
                .show(this, DIALOG_INACTIVE_BACKEND)
        }
    }

    open fun checkGdprConsent(forceShow: Boolean) {
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            adHandlerFactory.gdprConsent(this@BaseActivity, forceShow)
        }
    }


    companion object {
        const val ASYNC_TAG = "ASYNC_TASK"
        const val PROGRESS_TAG = "PROGRESS"
        private const val DIALOG_INACTIVE_BACKEND = "inactive_backend"
    }
}