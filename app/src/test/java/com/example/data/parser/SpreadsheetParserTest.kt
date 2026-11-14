package com.example.data.parser

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class SpreadsheetParserTest {

    @Test
    fun parseCsvStream_handlesHeadersAndDuplicates() {
        val csv = """
            Roll Number,Student Name
            CS101,Aarav Sharma
            CS102,"Patel, Diya"
            CS101,Duplicate Student
            ,Missing Roll
            CS103,Rohan Verma
        """.trimIndent()

        val result = SpreadsheetParser.parseCsvStream(
            "students.csv",
            ByteArrayInputStream(csv.toByteArray(Charsets.UTF_8))
        )

        assertEquals(3, result.students.size)
        assertEquals("CS101", result.students[0].rollNumber)
        assertEquals("Aarav Sharma", result.students[0].name)
        assertEquals("Patel, Diya", result.students[1].name)
        assertEquals("CS103", result.students[2].rollNumber)
        assertEquals(2, result.skippedRows)
    }

    @Test
    fun parseXlsxBytes_extractsSharedStringsAndNumericRolls() {
        val sharedStringsXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" count="4" uniqueCount="4">
                <si><t>Roll Number</t></si>
                <si><t>Name</t></si>
                <si><t>Meera Nair</t></si>
                <si><t>Kabir Joshi</t></si>
            </sst>
        """.trimIndent()

        val sheet1Xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                <sheetData>
                    <row r="1">
                        <c r="A1" t="s"><v>0</v></c>
                        <c r="B1" t="s"><v>1</v></c>
                    </row>
                    <row r="2">
                        <c r="A2"><v>101.0</v></c>
                        <c r="B2" t="s"><v>2</v></c>
                    </row>
                    <row r="3">
                        <c r="A3"><v>102</v></c>
                        <c r="B3" t="s"><v>3</v></c>
                    </row>
                </sheetData>
            </worksheet>
        """.trimIndent()

        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            zos.putNextEntry(ZipEntry("xl/sharedStrings.xml"))
            zos.write(sharedStringsXml.toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("xl/worksheets/sheet1.xml"))
            zos.write(sheet1Xml.toByteArray(Charsets.UTF_8))
            zos.closeEntry()
        }

        val result = SpreadsheetParser.parseXlsxBytes("roster.xlsx", baos.toByteArray())
        assertEquals(2, result.students.size)
        assertEquals("101", result.students[0].rollNumber)
        assertEquals("Meera Nair", result.students[0].name)
        assertEquals("102", result.students[1].rollNumber)
        assertEquals("Kabir Joshi", result.students[1].name)
    }
}
