package com.example.data.parser

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import com.example.data.model.ParsedStudentRow
import com.example.data.model.SpreadsheetParseResult
import org.w3c.dom.Element
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Parses both .csv and .xlsx (OpenXML) files directly in pure Kotlin without heavy POI binaries.
 * Supports header detection for Roll Number and Student Name columns in any order.
 */
object SpreadsheetParser {

    fun parseFromUri(contentResolver: ContentResolver, uri: Uri): SpreadsheetParseResult {
        val fileName = resolveFileName(contentResolver, uri) ?: "roster"
        val lowerName = fileName.lowercase()
        val mimeType = contentResolver.getType(uri).orEmpty().lowercase()

        val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalStateException("Unable to open selected file.")

        if (bytes.isEmpty()) {
            throw IllegalArgumentException("The selected file is empty.")
        }

        val isXlsx = lowerName.endsWith(".xlsx") ||
            mimeType.contains("spreadsheetml") ||
            (bytes.size >= 2 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte()) // PK zip header

        return if (isXlsx) {
            parseXlsxBytes(fileName, bytes)
        } else {
            parseCsvStream(fileName, ByteArrayInputStream(bytes))
        }
    }

    fun parseCsvStream(fileName: String, inputStream: InputStream): SpreadsheetParseResult {
        val rows = mutableListOf<List<String>>()
        BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8)).use { reader ->
            var lineNumber = 0
            while (true) {
                val rawLine = reader.readLine() ?: break
                val line = if (lineNumber == 0) rawLine.removePrefix("\uFEFF") else rawLine
                lineNumber++
                if (line.isBlank()) continue
                rows.add(parseCsvLine(line))
            }
        }
        return extractStudentRows(fileName, rows)
    }

    fun parseXlsxBytes(fileName: String, bytes: ByteArray): SpreadsheetParseResult {
        val sharedStrings = mutableListOf<String>()
        var firstSheetBytes: ByteArray? = null

        ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val entryName = entry.name
                if (!entry.isDirectory) {
                    when {
                        entryName == "xl/sharedStrings.xml" -> {
                            sharedStrings.addAll(parseSharedStringsXml(zis.readBytes()))
                        }
                        entryName == "xl/worksheets/sheet1.xml" -> {
                            firstSheetBytes = zis.readBytes()
                        }
                        firstSheetBytes == null &&
                            entryName.startsWith("xl/worksheets/sheet") &&
                            entryName.endsWith(".xml") -> {
                            firstSheetBytes = zis.readBytes()
                        }
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }

        val sheetData = firstSheetBytes
            ?: throw IllegalArgumentException("Could not find a worksheet inside the .xlsx file.")

        val rows = parseWorksheetXml(sheetData, sharedStrings)
        return extractStudentRows(fileName, rows)
    }

    private fun extractStudentRows(
        fileName: String,
        rawRows: List<List<String>>
    ): SpreadsheetParseResult {
        if (rawRows.isEmpty()) {
            return SpreadsheetParseResult(fileName, emptyList(), 0, listOf("File contains no rows."))
        }

        val warnings = mutableListOf<String>()
        val firstRow = rawRows.first().map { it.trim().lowercase() }

        var rollCol = firstRow.indexOfFirst {
            it.contains("roll") || it == "id" || it == "usn" || it == "reg" || it == "regno" || it.contains("registration")
        }
        var nameCol = firstRow.indexOfFirst {
            it.contains("name") || it == "student" || it == "full name"
        }

        val hasHeader = rollCol != -1 || nameCol != -1
        if (rollCol == -1) rollCol = 0
        if (nameCol == -1) nameCol = if (rollCol == 0) 1 else 0

        val dataRows = if (hasHeader) rawRows.drop(1) else rawRows
        val parsed = mutableListOf<ParsedStudentRow>()
        val seenRolls = mutableSetOf<String>()
        var skipped = 0

        dataRows.forEachIndexed { idx, cols ->
            val roll = cols.getOrNull(rollCol)?.trim().orEmpty()
            val name = cols.getOrNull(nameCol)?.trim().orEmpty()
            val rowNum = idx + (if (hasHeader) 2 else 1)

            when {
                roll.isEmpty() && name.isEmpty() -> {
                    // Blank trailing row, ignore silently
                }
                roll.isEmpty() || name.isEmpty() -> {
                    skipped++
                    if (warnings.size < 5) {
                        warnings.add("Row $rowNum skipped: missing Roll Number or Name.")
                    }
                }
                !seenRolls.add(roll.uppercase()) -> {
                    skipped++
                    if (warnings.size < 5) {
                        warnings.add("Row $rowNum skipped: duplicate Roll Number '$roll'.")
                    }
                }
                else -> {
                    parsed.add(ParsedStudentRow(rollNumber = roll, name = name))
                }
            }
        }

        return SpreadsheetParseResult(
            fileName = fileName,
            students = parsed,
            skippedRows = skipped,
            warnings = warnings
        )
    }

    private fun parseCsvLine(line: String): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            when {
                ch == '"' -> {
                    if (inQuotes && i + 1 < line.length && line[i + 1] == '"') {
                        current.append('"')
                        i++
                    } else {
                        inQuotes = !inQuotes
                    }
                }
                ch == ',' && !inQuotes -> {
                    result.add(current.toString())
                    current.clear()
                }
                else -> current.append(ch)
            }
            i++
        }
        result.add(current.toString())
        return result
    }

    private fun parseSharedStringsXml(bytes: ByteArray): List<String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(ByteArrayInputStream(bytes))
        val siNodes = doc.getElementsByTagName("si")
        val strings = mutableListOf<String>()
        for (i in 0 until siNodes.length) {
            val siElement = siNodes.item(i) as? Element ?: continue
            val tNodes = siElement.getElementsByTagName("t")
            val sb = StringBuilder()
            for (j in 0 until tNodes.length) {
                sb.append(tNodes.item(j).textContent.orEmpty())
            }
            strings.add(sb.toString())
        }
        return strings
    }

    private fun parseWorksheetXml(
        bytes: ByteArray,
        sharedStrings: List<String>
    ): List<List<String>> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(ByteArrayInputStream(bytes))
        val rowNodes = doc.getElementsByTagName("row")
        val rows = mutableListOf<List<String>>()

        for (i in 0 until rowNodes.length) {
            val rowElement = rowNodes.item(i) as? Element ?: continue
            val cellNodes = rowElement.getElementsByTagName("c")
            val currentRow = mutableMapOf<Int, String>()

            for (j in 0 until cellNodes.length) {
                val cellElement = cellNodes.item(j) as? Element ?: continue
                val cellRef = cellElement.getAttribute("r")
                val colIndex = if (cellRef.isNotBlank()) {
                    columnLettersToIndex(cellRef)
                } else {
                    (currentRow.keys.maxOrNull() ?: -1) + 1
                }
                val cellType = cellElement.getAttribute("t")

                val resolved = when (cellType) {
                    "s" -> {
                        val vText = cellElement.getElementsByTagName("v").item(0)?.textContent?.trim().orEmpty()
                        val idx = vText.toIntOrNull()
                        if (idx != null && idx in sharedStrings.indices) sharedStrings[idx] else ""
                    }
                    "inlineStr" -> {
                        val tNodes = cellElement.getElementsByTagName("t")
                        buildString {
                            for (k in 0 until tNodes.length) {
                                append(tNodes.item(k).textContent.orEmpty())
                            }
                        }.trim()
                    }
                    else -> {
                        val rawVal = cellElement.getElementsByTagName("v").item(0)?.textContent?.trim().orEmpty()
                        if (rawVal.endsWith(".0") && rawVal.dropLast(2).all { it.isDigit() }) {
                            rawVal.dropLast(2)
                        } else {
                            rawVal
                        }
                    }
                }
                if (resolved.isNotEmpty()) {
                    currentRow[colIndex] = resolved
                }
            }

            if (currentRow.isNotEmpty()) {
                val maxCol = currentRow.keys.maxOrNull() ?: 0
                rows.add((0..maxCol).map { currentRow[it].orEmpty() })
            }
        }
        return rows
    }

    private fun columnLettersToIndex(cellRef: String): Int {
        var col = 0
        for (ch in cellRef) {
            if (ch.isLetter()) {
                col = col * 26 + (ch.uppercaseChar() - 'A' + 1)
            } else {
                break
            }
        }
        return (col - 1).coerceAtLeast(0)
    }

    private fun resolveFileName(contentResolver: ContentResolver, uri: Uri): String? {
        if (uri.scheme == "content") {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0) return cursor.getString(idx)
                    }
                }
        }
        return uri.lastPathSegment
    }
}
