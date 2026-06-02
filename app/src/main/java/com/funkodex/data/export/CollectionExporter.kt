package com.funkodex.data.export

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.funkodex.data.model.FunkoItem
import com.funkodex.data.model.SeriesSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.poi.ss.usermodel.*
import org.apache.poi.ss.util.CellRangeAddress
import org.apache.poi.xssf.usermodel.*
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CollectionExporter @Inject constructor(
    private val context: Context
) {
    companion object {
        private val DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd")
        private val FILE_DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")

        // Column definitions for the main Collection sheet
        private val COLLECTION_COLS = listOf(
            "Name", "Series", "#", "Category", "UPC", "Funko ID",
            "Price Paid", "Retail Price", "Saved vs Retail",
            "Exclusive", "Retailer", "Condition", "Vaulted",
            "Date Added", "Date Acquired", "Notes"
        )

        // Column definitions for Series Completion sheet
        private val SERIES_COLS = listOf(
            "Series", "Owned", "Total in Series", "Missing", "Completion %",
            "Total Paid", "Avg Paid per Item"
        )

        // Column definitions for Want List sheet
        private val WANT_COLS = listOf(
            "Name", "Series", "#", "Category", "Retail Price",
            "Exclusive", "Retailer", "Vaulted"
        )
    }

    /**
     * Builds a .xlsx workbook with three sheets:
     *   1. Collection    — every owned item, one row each
     *   2. Series Report — completion stats per series
     *   3. Want List     — items marked isOwned=false
     *
     * Returns a content:// URI suitable for sharing via Intent.
     */
    suspend fun exportToXlsx(
        owned: List<FunkoItem>,
        wantList: List<FunkoItem>,
        seriesSummaries: List<SeriesSummary>,
    ): Result<Uri> = withContext(Dispatchers.IO) {
        runCatching {
            val workbook = XSSFWorkbook()
            val styles = buildStyles(workbook)

            writeCollectionSheet(workbook, styles, owned)
            writeSeriesSheet(workbook, styles, seriesSummaries)
            writeWantListSheet(workbook, styles, wantList)
            writeSummarySheet(workbook, styles, owned, wantList, seriesSummaries)

            val fileName = "FunkoDex_${LocalDate.now().format(FILE_DATE_FMT)}.xlsx"
            val file = File(context.cacheDir, fileName)
            FileOutputStream(file).use { workbook.write(it) }
            workbook.close()

            // FileProvider URI — shareable with other apps (Gmail, Files, etc.)
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        }
    }

    /**
     * Simpler CSV export — collection only, no formatting.
     * Useful for import into Google Sheets or Excel without the POI dependency.
     */
    suspend fun exportToCsv(owned: List<FunkoItem>): Result<Uri> = withContext(Dispatchers.IO) {
        runCatching {
            val sb = StringBuilder()
            sb.appendLine(COLLECTION_COLS.joinToString(",") { "\"$it\"" })
            owned.forEach { item ->
                sb.appendLine(buildCsvRow(item))
            }
            val fileName = "FunkoDex_${LocalDate.now().format(FILE_DATE_FMT)}.csv"
            val file = File(context.cacheDir, fileName)
            file.writeText(sb.toString())
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        }
    }

    // ─── Sheet writers ─────────────────────────────────────────────────────────

    private fun writeCollectionSheet(
        wb: XSSFWorkbook, styles: SheetStyles, items: List<FunkoItem>
    ) {
        val sheet = wb.createSheet("Collection")
        writeHeader(sheet, styles, COLLECTION_COLS)

        items.sortedWith(compareBy({ it.franchise }, { it.seriesNumber }))
            .forEachIndexed { i, item ->
                val row = sheet.createRow(i + 1)
                val saved = item.retailPrice - item.pricePaid

                row.cellStr(0, item.name, styles.body)
                row.cellStr(1, item.franchise, styles.body)
                row.cellStr(2, item.seriesNumber, styles.body)
                row.cellStr(3, item.category, styles.body)
                row.cellStr(4, item.upc, styles.body)
                row.cellStr(5, item.funkoId, styles.body)
                row.cellNum(6, item.pricePaid, styles.currency)
                row.cellNum(7, item.retailPrice, styles.currency)
                row.cellNum(8, saved, if (saved >= 0) styles.currencyGreen else styles.currencyRed)
                row.cellStr(9, if (item.isExclusive) "Yes" else "No", styles.body)
                row.cellStr(10, item.exclusiveRetailer, styles.body)
                row.cellStr(11, item.condition.name, styles.body)
                row.cellStr(12, if (item.isVaulted) "Yes" else "No", styles.body)
                row.cellStr(13, item.dateAdded.format(DATE_FMT), styles.body)
                row.cellStr(14, item.dateAcquired?.format(DATE_FMT) ?: "", styles.body)
                row.cellStr(15, item.notes, styles.body)
            }

        // Totals row
        val totalsRow = sheet.createRow(items.size + 1)
        totalsRow.cellStr(0, "TOTALS", styles.headerCell)
        totalsRow.cellNum(6, items.sumOf { it.pricePaid }, styles.currencyBold)
        totalsRow.cellNum(7, items.sumOf { it.retailPrice }, styles.currencyBold)
        val totalSaved = items.sumOf { it.retailPrice - it.pricePaid }
        totalsRow.cellNum(8, totalSaved, if (totalSaved >= 0) styles.currencyGreenBold else styles.currencyRedBold)

        autoSizeColumns(sheet, COLLECTION_COLS.size)
        sheet.setColumnWidth(0, 30 * 256)   // Name — wider
        sheet.setColumnWidth(15, 40 * 256)  // Notes — wider
        sheet.createFreezePane(0, 1)         // Freeze header row
    }

    private fun writeSeriesSheet(
        wb: XSSFWorkbook, styles: SheetStyles, series: List<SeriesSummary>
    ) {
        val sheet = wb.createSheet("Series Completion")
        writeHeader(sheet, styles, SERIES_COLS)

        series.sortedByDescending { it.completionPct }.forEachIndexed { i, s ->
            val row = sheet.createRow(i + 1)
            row.cellStr(0, s.franchise, styles.body)
            row.cellNum(1, s.ownedCount.toDouble(), styles.integer)
            row.cellNum(2, s.totalInCatalog.toDouble(), styles.integer)
            row.cellNum(3, s.missingItems.size.toDouble(), styles.integer)
            row.cellNum(4, s.completionPct.toDouble() / 100.0, styles.percent)
            row.cellNum(5, s.totalCostPaid, styles.currency)
            val avg = if (s.ownedCount > 0) s.totalCostPaid / s.ownedCount else 0.0
            row.cellNum(6, avg, styles.currency)
        }

        autoSizeColumns(sheet, SERIES_COLS.size)
        sheet.createFreezePane(0, 1)
    }

    private fun writeWantListSheet(
        wb: XSSFWorkbook, styles: SheetStyles, items: List<FunkoItem>
    ) {
        val sheet = wb.createSheet("Want List")
        writeHeader(sheet, styles, WANT_COLS)

        items.sortedWith(compareBy({ it.franchise }, { it.seriesNumber }))
            .forEachIndexed { i, item ->
                val row = sheet.createRow(i + 1)
                row.cellStr(0, item.name, styles.body)
                row.cellStr(1, item.franchise, styles.body)
                row.cellStr(2, item.seriesNumber, styles.body)
                row.cellStr(3, item.category, styles.body)
                row.cellNum(4, item.retailPrice, styles.currency)
                row.cellStr(5, if (item.isExclusive) "Yes" else "No", styles.body)
                row.cellStr(6, item.exclusiveRetailer, styles.body)
                row.cellStr(7, if (item.isVaulted) "Yes" else "No", styles.body)
            }

        autoSizeColumns(sheet, WANT_COLS.size)
        sheet.createFreezePane(0, 1)
    }

    private fun writeSummarySheet(
        wb: XSSFWorkbook, styles: SheetStyles,
        owned: List<FunkoItem>, wantList: List<FunkoItem>,
        series: List<SeriesSummary>
    ) {
        val sheet = wb.createSheet("Summary")
        // Move Summary to be first sheet
        wb.setSheetOrder("Summary", 0)

        fun summaryRow(label: String, value: String, rowIdx: Int) {
            val row = sheet.createRow(rowIdx)
            row.cellStr(0, label, styles.summaryLabel)
            row.cellStr(1, value, styles.summaryValue)
        }

        val totalPaid = owned.sumOf { it.pricePaid }
        val totalRetail = owned.sumOf { it.retailPrice }

        sheet.createRow(0).cellStr(0, "FunkoDex Collection Report", styles.titleCell)
        sheet.addMergedRegion(CellRangeAddress(0, 0, 0, 3))

        sheet.createRow(1).cellStr(0, "Generated: ${LocalDate.now().format(DATE_FMT)}", styles.body)

        summaryRow("Total items owned",       owned.size.toString(),                2 + 1)
        summaryRow("Total on want list",       wantList.size.toString(),             2 + 2)
        summaryRow("Unique series",            series.size.toString(),               2 + 3)
        summaryRow("Total paid",               "$${"%.2f".format(totalPaid)}",       2 + 4)
        summaryRow("Total retail value",       "$${"%.2f".format(totalRetail)}",     2 + 5)
        summaryRow("Saved vs retail",          "$${"%.2f".format(totalRetail - totalPaid)}", 2 + 6)
        summaryRow("Exclusive items",          owned.count { it.isExclusive }.toString(), 2 + 7)
        summaryRow("Vaulted items",            owned.count { it.isVaulted }.toString(), 2 + 8)
        summaryRow("Most expensive (paid)",    owned.maxByOrNull { it.pricePaid }
            ?.let { "${it.name}  $${"%.2f".format(it.pricePaid)}" } ?: "—", 2 + 9)
        summaryRow("Most complete series",     series.maxByOrNull { it.completionPct }
            ?.let { "${it.franchise}  ${it.completionPct}%" } ?: "—", 2 + 10)

        sheet.setColumnWidth(0, 28 * 256)
        sheet.setColumnWidth(1, 35 * 256)
    }

    // ─── Style helpers ─────────────────────────────────────────────────────────

    private data class SheetStyles(
        val headerCell: XSSFCellStyle,
        val body: XSSFCellStyle,
        val currency: XSSFCellStyle,
        val currencyBold: XSSFCellStyle,
        val currencyGreen: XSSFCellStyle,
        val currencyRed: XSSFCellStyle,
        val currencyGreenBold: XSSFCellStyle,
        val currencyRedBold: XSSFCellStyle,
        val percent: XSSFCellStyle,
        val integer: XSSFCellStyle,
        val titleCell: XSSFCellStyle,
        val summaryLabel: XSSFCellStyle,
        val summaryValue: XSSFCellStyle,
    )

    private fun buildStyles(wb: XSSFWorkbook): SheetStyles {
        val headerFill = wb.createCellStyle() as XSSFCellStyle
        headerFill.setFillForegroundColor(XSSFColor(byteArrayOf(0xE8.toByte(), 0x40.toByte(), 0x1A.toByte()), null))
        headerFill.fillPattern = FillPatternType.SOLID_FOREGROUND
        val headerFont = wb.createFont()
        headerFont.bold = true
        headerFont.color = IndexedColors.WHITE.index
        headerFill.setFont(headerFont)
        headerFill.borderBottom = BorderStyle.THIN

        val bodyStyle = wb.createCellStyle() as XSSFCellStyle
        val altFill = wb.createCellStyle() as XSSFCellStyle

        val currFmt = wb.createDataFormat().getFormat("$#,##0.00")
        val pctFmt  = wb.createDataFormat().getFormat("0%")
        val intFmt  = wb.createDataFormat().getFormat("0")

        fun currStyle(bold: Boolean = false, color: Short? = null): XSSFCellStyle {
            val s = wb.createCellStyle() as XSSFCellStyle
            s.dataFormat = currFmt
            if (bold || color != null) {
                val f = wb.createFont()
                if (bold) f.bold = true
                if (color != null) f.color = color
                s.setFont(f)
            }
            return s
        }

        val boldFont = wb.createFont().also { it.bold = true }
        val titleStyle = (wb.createCellStyle() as XSSFCellStyle).also {
            val f = wb.createFont(); f.bold = true; f.fontHeightInPoints = 14
            it.setFont(f)
        }
        val labelStyle = (wb.createCellStyle() as XSSFCellStyle).also {
            val f = wb.createFont(); f.bold = true
            it.setFont(f)
        }

        val pctStyle = (wb.createCellStyle() as XSSFCellStyle).also { it.dataFormat = pctFmt }
        val intStyle = (wb.createCellStyle() as XSSFCellStyle).also { it.dataFormat = intFmt }
        val boldBodyStyle = (wb.createCellStyle() as XSSFCellStyle).also { it.setFont(boldFont) }

        return SheetStyles(
            headerCell      = headerFill,
            body            = bodyStyle,
            currency        = currStyle(),
            currencyBold    = currStyle(bold = true),
            currencyGreen   = currStyle(color = IndexedColors.GREEN.index),
            currencyRed     = currStyle(color = IndexedColors.RED.index),
            currencyGreenBold = currStyle(bold = true, color = IndexedColors.GREEN.index),
            currencyRedBold   = currStyle(bold = true, color = IndexedColors.RED.index),
            percent         = pctStyle,
            integer         = intStyle,
            titleCell       = titleStyle,
            summaryLabel    = labelStyle,
            summaryValue    = bodyStyle,
        )
    }

    private fun writeHeader(sheet: Sheet, styles: SheetStyles, cols: List<String>) {
        val row = sheet.createRow(0)
        cols.forEachIndexed { i, label ->
            row.cellStr(i, label, styles.headerCell)
        }
    }

    private fun autoSizeColumns(sheet: Sheet, count: Int) {
        for (i in 0 until count) sheet.autoSizeColumn(i)
    }

    // ─── CSV helper ────────────────────────────────────────────────────────────

    private fun buildCsvRow(item: FunkoItem): String {
        val saved = item.retailPrice - item.pricePaid
        return listOf(
            item.name, item.franchise, item.seriesNumber, item.category, item.upc, item.funkoId,
            "%.2f".format(item.pricePaid), "%.2f".format(item.retailPrice), "%.2f".format(saved),
            if (item.isExclusive) "Yes" else "No", item.exclusiveRetailer,
            item.condition.name, if (item.isVaulted) "Yes" else "No",
            item.dateAdded.format(DATE_FMT), item.dateAcquired?.format(DATE_FMT) ?: "",
            item.notes
        ).joinToString(",") { "\"${it.replace("\"", "\"\"")}\"" }
    }

    // ─── Row extension helpers ─────────────────────────────────────────────────

    private fun Row.cellStr(col: Int, value: String, style: CellStyle) {
        createCell(col).also { it.setCellValue(value); it.cellStyle = style }
    }

    private fun Row.cellNum(col: Int, value: Double, style: CellStyle) {
        createCell(col, CellType.NUMERIC).also { it.setCellValue(value); it.cellStyle = style }
    }
}
