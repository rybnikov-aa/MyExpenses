package org.totschnig.myexpenses.test.espresso

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.totschnig.myexpenses.R
import org.totschnig.myexpenses.activity.ExpenseEdit
import org.totschnig.myexpenses.activity.TestExpenseEdit
import org.totschnig.myexpenses.contract.TransactionsContract.Transactions
import org.totschnig.myexpenses.db2.getTransactionSum
import org.totschnig.myexpenses.model.AccountType
import org.totschnig.myexpenses.model.CurrencyUnit
import org.totschnig.myexpenses.model.Template
import org.totschnig.myexpenses.model2.Account
import org.totschnig.myexpenses.provider.DatabaseConstants
import org.totschnig.myexpenses.testutils.Espresso.*
import java.util.*

class ExpenseEditTest : BaseExpenseEditTest() {
    private lateinit var account2: Account
    private lateinit var yenAccount: Account
    private lateinit var currency1: CurrencyUnit
    private lateinit var currency2: CurrencyUnit

    @Before
    fun fixture() {
        currency1 = CurrencyUnit(Currency.getInstance("USD"))
        currency2 = CurrencyUnit(Currency.getInstance("EUR"))
        account1 = Account(label = "Test label 1", currency = currency1.code).createIn(repository)
        account2 =
            Account(label = "Test label 2", currency = currency2.code, type = AccountType.BANK)
                .createIn(repository)
        yenAccount =
            Account(label = "Japan", currency = "JPY").createIn(repository)
    }

    private fun launch(i: Intent) = ActivityScenario.launch<TestExpenseEdit>(i).also {
        testScenario = it
    }

    @Test
    fun formForTransactionIsPrepared() {
        launch(intentForNewTransaction.apply {
            putExtra(Transactions.OPERATION_TYPE, Transactions.TYPE_TRANSACTION)
        }).use {
            checkEffectiveVisible(
                R.id.DateTimeRow, R.id.AmountRow, R.id.CommentRow, R.id.CategoryRow,
                R.id.PayeeRow, R.id.AccountRow
            )
            checkEffectiveGone(R.id.Status, R.id.Recurrence, R.id.TitleRow)
            clickMenuItem(R.id.CREATE_TEMPLATE_COMMAND)
            checkEffectiveVisible(R.id.TitleRow, R.id.Recurrence)
            checkAccountDependents()
        }
    }

    private fun checkAccountDependents() {
        onView(withId(R.id.AmountLabel)).check(
            matches(withText("${getString(R.string.amount)} (${currency1.symbol})"))
        )
        onView(withId(R.id.DateTimeLabel)).check(
            matches(withText("${getString(R.string.date)} / ${getString(R.string.time)}"))
        )
    }

    @Test
    fun statusIsShownWhenBankAccountIsSelected() {
        launch(intent.apply {
            putExtra(Transactions.OPERATION_TYPE, Transactions.TYPE_TRANSACTION)
            putExtra(DatabaseConstants.KEY_ACCOUNTID, account2.id)
        }).use {
            checkEffectiveVisible(R.id.Status)
        }
    }

    @Test
    fun amountInputWithFractionDigitLessCurrency() {
        launch(intent.apply {
            putExtra(DatabaseConstants.KEY_ACCOUNTID, yenAccount.id)
        }).use {
            setAmount(100)
        }
    }

    @Test
    fun formForTransferIsPrepared() {
        launch(intentForNewTransaction.apply {
            putExtra(Transactions.OPERATION_TYPE, Transactions.TYPE_TRANSFER)
        }).use {
            checkEffectiveVisible(
                R.id.DateTimeRow, R.id.AmountRow, R.id.CommentRow, R.id.AccountRow,
                R.id.TransferAccountRow
            )
            checkEffectiveGone(R.id.Status, R.id.Recurrence, R.id.TitleRow)
            clickMenuItem(R.id.CREATE_TEMPLATE_COMMAND)
            checkEffectiveVisible(R.id.TitleRow, R.id.Recurrence)
            checkAccountDependents()

        }
    }

    @Test
    fun formForSplitIsPrepared() {
        launch(intentForNewTransaction.apply {
            putExtra(Transactions.OPERATION_TYPE, Transactions.TYPE_SPLIT)
        }).use {
            checkEffectiveVisible(
                R.id.DateTimeRow, R.id.AmountRow, R.id.CommentRow, R.id.SplitRow,
                R.id.PayeeRow, R.id.AccountRow
            )
            checkEffectiveGone(R.id.Status, R.id.Recurrence, R.id.TitleRow)
            clickMenuItem(R.id.CREATE_TEMPLATE_COMMAND)
            checkEffectiveVisible(R.id.TitleRow, R.id.Recurrence)
            checkAccountDependents()
        }
    }

    @Test
    fun formForTemplateIsPrepared() {
        //New Templates are created without account_id passed in via intent
        launch(intent.apply {
            putExtra(Transactions.OPERATION_TYPE, Transactions.TYPE_TRANSACTION)
            putExtra(ExpenseEdit.KEY_NEW_TEMPLATE, true)
        }).use {
            checkEffectiveVisible(
                R.id.TitleRow, R.id.AmountRow, R.id.CommentRow, R.id.CategoryRow,
                R.id.PayeeRow, R.id.AccountRow, R.id.Recurrence, R.id.DefaultActionRow
            )
            checkEffectiveGone(R.id.PB)
        }
    }

    @Test
    fun accountIdInExtraShouldPopulateSpinner() {
        val allAccounts = arrayOf(account1, account2)
        for (a in allAccounts) {
            val i = intent.apply {
                putExtra(Transactions.OPERATION_TYPE, Transactions.TYPE_TRANSACTION)
                putExtra(DatabaseConstants.KEY_ACCOUNTID, a.id)
            }
            launch(i).use {
                onView(withId(R.id.Account)).check(
                    matches(withSpinnerText(a.label))
                )
            }
        }
    }

    @Test
    fun saveAsNewWorksMultipleTimesInARow() {
        launch(intent.apply {
            putExtra(Transactions.OPERATION_TYPE, Transactions.TYPE_TRANSACTION)
            putExtra(DatabaseConstants.KEY_ACCOUNTID, account1.id)
        }).use {
            val success = getString(R.string.save_transaction_and_new_success)
            val times = 5
            val amount = 2
            clickMenuItem(R.id.SAVE_AND_NEW_COMMAND, false) //toggle save and new on
            for (j in 0 until times) {
                setAmount(amount)
                clickFab()
                onView(withText(success)).check(matches(isDisplayed()))
            }
            //we assume two fraction digits
            assertEquals(
                "Transaction sum does not match saved transactions",
                repository.getTransactionSum(account1.id),
                (-amount * times * 100).toLong()
            )
        }
    }

    @Test
    fun shouldSaveTemplateWithAmount() {
        val template =
            Template.getTypedNewInstance(contentResolver, Transactions.TYPE_TRANSFER, account1.id, currency1, false, null)
        template!!.setTransferAccountId(account2.id)
        template.title = "Test template"
        template.save(contentResolver)
        launch(intent.apply {
            putExtra(DatabaseConstants.KEY_TEMPLATEID, template.id)
        }).use {
            val amount = 2
            setAmount(amount)
            clickFab()
            val restored = Template.getInstanceFromDb(contentResolver, template.id)
            assertEquals(Transactions.TYPE_TRANSFER, restored!!.operationType())
            assertEquals((-amount * 100).toLong(), restored.amount.amountMinor)
        }
    }
}