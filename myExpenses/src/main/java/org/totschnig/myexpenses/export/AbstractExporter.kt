package org.totschnig.myexpenses.export

import android.content.Context
import android.database.Cursor
import android.net.Uri
import androidx.core.net.toFile
import androidx.documentfile.provider.DocumentFile
import org.totschnig.myexpenses.R
import org.totschnig.myexpenses.db2.localizedLabelSqlColumn
import org.totschnig.myexpenses.model.*
import org.totschnig.myexpenses.model.Transaction
import org.totschnig.myexpenses.model2.Account
import org.totschnig.myexpenses.provider.DatabaseConstants.*
import org.totschnig.myexpenses.provider.TRANSFER_ACCOUNT_LABEL
import org.totschnig.myexpenses.provider.TransactionProvider
import org.totschnig.myexpenses.provider.TransactionProvider.TRANSACTIONS_ATTACHMENTS_URI
import org.totschnig.myexpenses.provider.asSequence
import org.totschnig.myexpenses.provider.fileName
import org.totschnig.myexpenses.provider.filter.WhereFilter
import org.totschnig.myexpenses.provider.getLongOrNull
import org.totschnig.myexpenses.provider.getString
import org.totschnig.myexpenses.provider.getStringOrNull
import org.totschnig.myexpenses.provider.useAndMapToList
import org.totschnig.myexpenses.util.Utils
import org.totschnig.myexpenses.util.enumValueOrDefault
import org.totschnig.myexpenses.util.epoch2ZonedDateTime
import timber.log.Timber
import java.io.IOException
import java.io.OutputStreamWriter
import java.time.format.DateTimeFormatter

abstract class AbstractExporter
/**
 * @param account          Account to print
 * @param filter           only transactions matched by filter will be considered
 * @param notYetExportedP  if true only transactions not marked as exported will be handled
 * @param dateFormat       format that can be parsed by SimpleDateFormat class
 * @param decimalSeparator , or .
 * @param encoding         the string describing the desired character encoding.
 */
    (
    val account: Account,
    val currencyContext: CurrencyContext,
    private val filter: WhereFilter?,
    private val notYetExportedP: Boolean,
    private val dateFormat: String,
    private val decimalSeparator: Char,
    private val encoding: String
) {

    val currencyUnit = currencyContext.get(account.currency)

    val openingBalance = Money(currencyUnit, account.openingBalance).amountMajor

    val nfFormat = Utils.getDecimalFormat(currencyUnit, decimalSeparator)

    val dateFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern(dateFormat)

    abstract val format: ExportFormat

    abstract fun header(context: Context): String?

    abstract fun TransactionDTO.marshall(categoryPaths: Map<Long, List<String>>): String

    open val useCategoryOfFirstPartForParent = true

    private val categoryTree: MutableMap<Long, Pair<String, Long>> = mutableMapOf()
    val categoryPaths: MutableMap<Long, List<String>> = mutableMapOf()

    @Throws(IOException::class)
    open fun export(
        context: Context,
        outputStream: Lazy<Result<DocumentFile>>,
        append: Boolean
    ): Result<DocumentFile> {
        Timber.i("now starting export")
        context.contentResolver.query(
            TransactionProvider.CATEGORIES_URI,
            arrayOf(KEY_ROWID, KEY_LABEL, KEY_PARENTID), null, null, null
        )?.use { cursor ->
            cursor.asSequence.forEach {
                categoryTree[it.getLong(0)] = it.getString(1) to it.getLong(2)
            }
        }
        //first we check if there are any exportable transactions
        var selection = "$KEY_PARENTID is null"
        if (notYetExportedP) selection += " AND $KEY_STATUS = $STATUS_NONE"
        var selectionArgs = if (filter != null && !filter.isEmpty) {
            selection += " AND " + filter.getSelectionForParents(VIEW_EXTENDED, true)
            filter.getSelectionArgs(false)
        } else null
        val projection = arrayOf(
            KEY_UUID,
            KEY_ROWID,
            KEY_CATID,
            KEY_DATE,
            KEY_PAYEE_NAME,
            KEY_AMOUNT,
            KEY_COMMENT,
            localizedLabelSqlColumn(
                context,
                KEY_METHOD_LABEL
            ) + " AS " + KEY_METHOD_LABEL,
            KEY_CR_STATUS,
            KEY_REFERENCE_NUMBER,
            TRANSFER_ACCOUNT_LABEL
        )

        fun Cursor.ingestCategoryPaths() {
            asSequence.forEach { cursor ->
                cursor.getLongOrNull(KEY_CATID)?.takeIf { it != SPLIT_CATID }?.let { categoryId ->
                    categoryPaths.computeIfAbsent(categoryId) {
                        var catId: Long? = categoryId
                        buildList {
                            while (catId != null) {
                                val pair = categoryTree[catId]
                                catId = if (pair == null) {
                                    null
                                } else {
                                    add(pair.first)
                                    pair.second
                                }
                            }
                        }.reversed()
                    }
                }
            }
        }

        fun Cursor.toDTO(isPart: Boolean = false): TransactionDTO {
            val rowId = getLong(getColumnIndexOrThrow(KEY_ROWID))
            val catId = getLongOrNull(KEY_CATID)
            val isSplit = SPLIT_CATID == catId
            val splitCursor = if (isSplit) context.contentResolver.query(
                Transaction.CONTENT_URI,
                projection,
                "$KEY_PARENTID = ?",
                arrayOf(rowId.toString()),
                null
            ) else null
            val readCat =
                splitCursor?.takeIf { useCategoryOfFirstPartForParent && it.moveToFirst() } ?: this

            //noinspection Recycle
            val tagList = context.contentResolver.query(
                TransactionProvider.TRANSACTIONS_TAGS_URI,
                arrayOf(KEY_LABEL),
                "$KEY_TRANSACTIONID = ?",
                arrayOf(rowId.toString()),
                null
            )?.useAndMapToList { it.getString(0) }?.takeIf { it.isNotEmpty() }

            //noinspection Recycle
            val attachmentList = context.contentResolver.query(
                TRANSACTIONS_ATTACHMENTS_URI,
                arrayOf(KEY_URI),
                "$KEY_TRANSACTIONID = ?", arrayOf(rowId.toString()),
                null
            )?.useAndMapToList {
                val uri = Uri.parse(it.getString(0))
                //We should only see file uri from unit test
                if (uri.scheme == "file") uri.toFile().name else uri.fileName(context)
            }?.takeIf { it.isNotEmpty() }?.filterNotNull()

            val transactionDTO = TransactionDTO(
                getString(KEY_UUID),
                epoch2ZonedDateTime(getLong(getColumnIndexOrThrow(KEY_DATE))),
                getStringOrNull(KEY_PAYEE_NAME),
                Money(currencyUnit, getLong(getColumnIndexOrThrow(KEY_AMOUNT))).amountMajor,
                readCat.getLongOrNull(KEY_CATID),
                readCat.getStringOrNull(KEY_TRANSFER_ACCOUNT_LABEL),
                getStringOrNull(KEY_COMMENT)?.takeIf { it.isNotEmpty() },
                if (isPart) null else getString(getColumnIndexOrThrow(KEY_METHOD_LABEL)),
                if (isPart) null else
                    enumValueOrDefault(
                        getString(getColumnIndexOrThrow(KEY_CR_STATUS)),
                        CrStatus.UNRECONCILED
                    ),
                if (isPart) null else getStringOrNull(KEY_REFERENCE_NUMBER)
                    ?.takeIf { it.isNotEmpty() },
                attachmentList,
                tagList,
                splitCursor?.let { splits ->
                    splits.moveToPosition(-1)
                    splits.ingestCategoryPaths()
                    splits.moveToPosition(-1)
                    splits.asSequence.map {
                        it.toDTO(isPart = true)
                    }.toList()
                }
            )
            splitCursor?.close()
            return transactionDTO
        }

        return context.contentResolver.query(
            account.uriForTransactionList(), projection, selection, selectionArgs, KEY_DATE
        )?.use { cursor ->

            if (cursor.count == 0) {
                Result.failure(Exception(context.getString(R.string.no_exportable_expenses)))
            } else {
                cursor.ingestCategoryPaths()

                val output = outputStream.value.getOrThrow()
                (context.contentResolver.openOutputStream(output.uri, if (append) "wa" else "w")
                    ?: throw IOException("openOutputStream returned null")).use { outputStream ->
                    OutputStreamWriter(outputStream, encoding).use { out ->
                        cursor.moveToFirst()
                        header(context)?.let { out.write(it) }
                        while (cursor.position < cursor.count) {
                            out.write(cursor.toDTO().marshall(categoryPaths))

                            recordDelimiter(cursor.position == cursor.count - 1)?.let { out.write(it) }

                            cursor.moveToNext()
                        }

                        footer()?.let { out.write(it) }

                        Result.success(output)
                    }
                }
            }
        } ?: Result.failure(Exception("Cursor is null"))
    }

    open fun recordDelimiter(isLastLine: Boolean): String? = "\n"

    open fun footer(): String? = null

    companion object {
        const val ENCODING_UTF_8 = "UTF-8"
        const val ENCODING_LATIN_1 = "ISO-8859-1"
    }
}