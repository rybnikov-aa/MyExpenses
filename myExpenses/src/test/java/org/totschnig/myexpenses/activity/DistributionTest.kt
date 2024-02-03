package org.totschnig.myexpenses.activity

import android.content.Context
import android.content.Intent
import androidx.annotation.StringRes
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.CoreMatchers.allOf
import org.hamcrest.CoreMatchers.containsString
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.totschnig.myexpenses.BaseTestWithRepository
import org.totschnig.myexpenses.R
import org.totschnig.myexpenses.db2.saveCategory
import org.totschnig.myexpenses.model.CurrencyUnit
import org.totschnig.myexpenses.model.Money
import org.totschnig.myexpenses.model.Transaction
import org.totschnig.myexpenses.model2.Account
import org.totschnig.myexpenses.model2.Category
import org.totschnig.myexpenses.provider.DatabaseConstants.KEY_ACCOUNTID
import java.util.*

@Ignore("Robolectric does not seem to be able interact with Compose Popups, we run this connected at the moment")
@RunWith(AndroidJUnit4::class)
class DistributionTest : BaseTestWithRepository(){
    private lateinit var scenario: ActivityScenario<DistributionActivity>
    @get:Rule
    val composeTestRule = createEmptyComposeRule()
    private val targetContext: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val currency = CurrencyUnit.DebugInstance
    private lateinit var account: Account
    private var categoryId: Long = 0

    private fun baseFixture(additionalFixture: () -> Unit = {}) {
        account = Account(label = "Test account 1", currency = currency.code).createIn(repository)
        additionalFixture()
        scenario = ActivityScenario.launch(Intent(targetContext, DistributionActivity::class.java).apply {
            putExtra(KEY_ACCOUNTID, account.id)
        })
    }

    private fun fixtureWithMappedTransaction() {
        baseFixture {
            categoryId =  repository.saveCategory(Category(label = "TestCategory"))!!
            with(Transaction.getNewInstance(account.id, currency)) {
                amount = Money(CurrencyUnit.DebugInstance, -1200L)
                catId = categoryId
                save(repository.contentResolver)
            }
        }
    }

    @Test
    fun testSelectCommand() {
        launchWithContextCommand(R.string.menu_show_transactions)
        onView(allOf(withText(containsString("TestCategory")), withText(containsString("12"))))
            .inRoot(isDialog())
            .check(matches(isDisplayed()))
    }

    @Test
    fun testColorCommand() {
        launchWithContextCommand(R.string.color)
        scenario.onActivity {
            assertThat(it.supportFragmentManager.findFragmentByTag(ProtectedFragmentActivity.EDIT_COLOR_DIALOG)).isNotNull()
        }
    }

    private fun launchWithContextCommand(@StringRes menuLabel: Int) {
        fixtureWithMappedTransaction()
        composeTestRule
            .onNodeWithText("TestCategory").performTouchInput {
                longClick()
            }
        onContextMenu(menuLabel)
    }

    private fun onContextMenu(@StringRes menuItemId: Int) =
        composeTestRule.onNodeWithText(targetContext.getString(menuItemId)).performClick()

}