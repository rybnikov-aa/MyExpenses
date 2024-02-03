package org.totschnig.myexpenses

import android.content.ContentResolver
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.test.core.app.ApplicationProvider
import org.mockito.ArgumentMatchers
import org.mockito.Mockito
import org.totschnig.myexpenses.db2.Repository
import org.totschnig.myexpenses.db2.saveCategory
import org.totschnig.myexpenses.model.CurrencyContext
import org.totschnig.myexpenses.model.CurrencyUnit
import org.totschnig.myexpenses.model2.Category
import org.totschnig.myexpenses.preference.PrefHandler
import org.totschnig.myexpenses.util.CurrencyFormatter
import org.totschnig.myexpenses.util.locale.HomeCurrencyProvider
import java.util.*

open class BaseTestWithRepository {
    val currencyContext: CurrencyContext =
        Mockito.mock(CurrencyContext::class.java).also { currencyContext ->
            Mockito.`when`(currencyContext.get(ArgumentMatchers.anyString())).thenAnswer {
                CurrencyUnit(Currency.getInstance(it.getArgument(0) as String))
            }
        }
    val repository: Repository = Repository(
        ApplicationProvider.getApplicationContext<MyApplication>(),
        currencyContext,
        Mockito.mock(CurrencyFormatter::class.java),
        Mockito.mock(PrefHandler::class.java),
        Mockito.mock(HomeCurrencyProvider::class.java),
        Mockito.mock(DataStore::class.java) as DataStore<Preferences>
    )

    val contentResolver: ContentResolver = repository.contentResolver

    fun writeCategory(label: String, parentId: Long? = null) =
        repository.saveCategory(Category(label = label, parentId = parentId))!!

}