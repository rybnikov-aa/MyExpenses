package org.totschnig.myexpenses.widget

import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.totschnig.myexpenses.MyApplication
import org.totschnig.myexpenses.R
import org.totschnig.myexpenses.activity.ExpenseEdit
import org.totschnig.myexpenses.activity.ManageTemplates
import org.totschnig.myexpenses.model.Transaction
import org.totschnig.myexpenses.preference.PrefKey
import org.totschnig.myexpenses.provider.DatabaseConstants
import org.totschnig.myexpenses.provider.TransactionProvider
import org.totschnig.myexpenses.util.doAsync

const val CLICK_ACTION_SAVE = "save"
const val CLICK_ACTION_EDIT = "edit"

class TemplateWidget: AbstractListWidget(TemplateWidgetService::class.java, PrefKey.PROTECTION_ENABLE_TEMPLATE_WIDGET) {
    override val emptyTextResourceId = R.string.no_templates

    override fun handleWidgetClick(context: Context, intent: Intent) {
        val templateId = intent.getLongExtra(DatabaseConstants.KEY_ROWID, 0)
        when (intent.getStringExtra(KEY_CLICK_ACTION)) {
            null -> {
                context.startActivity(Intent(context, ManageTemplates::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    putExtra(EXTRA_START_FROM_WIDGET, true)
                })
            }
            CLICK_ACTION_SAVE -> {
                if (MyApplication.instance.shouldLock(null)) {
                    Toast.makeText(context,
                            context.getString(R.string.warning_instantiate_template_from_widget_password_protected),
                            Toast.LENGTH_LONG).show()
                } else {
                        doAsync {
                            Transaction.getInstanceFromTemplateWithTags(context.contentResolver, templateId)?.let {
                            if (it.first.save(context.contentResolver, true) != null) {
                                it.first.saveTags(context.contentResolver, it.second)
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(context.forToast(),
                                        context.resources.getQuantityString(R.plurals.save_transaction_from_template_success, 1, 1),
                                        Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    }
                }
            }
            CLICK_ACTION_EDIT -> context.startActivity(Intent(context, ExpenseEdit::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                action = ExpenseEdit.ACTION_CREATE_FROM_TEMPLATE
                putExtra(DatabaseConstants.KEY_TEMPLATEID, templateId)
                putExtra(EXTRA_START_FROM_WIDGET, true)
                putExtra(EXTRA_START_FROM_WIDGET_DATA_ENTRY, true)
            })
        }
    }

    private fun Context.forToast() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        createDisplayContext(getSystemService(DisplayManager::class.java)
            .getDisplay(Display.DEFAULT_DISPLAY))
            .createWindowContext(TYPE_APPLICATION_OVERLAY, null)
    } else {
        this
    }

    companion object {
        val OBSERVED_URIS = arrayOf(
                TransactionProvider.TEMPLATES_URI,
                TransactionProvider.ACCOUNTS_URI //if color changes
        )

    }
}