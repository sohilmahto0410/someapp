package com.sohil.icaibatchmonitor

import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.util.concurrent.TimeUnit

/**
 * Scrapes the ICAI Online Registration Portal to fetch batch availability.
 * URL: https://www.icaionlineregistration.org/LaunchBatchDetail.aspx
 *
 * The site uses ASP.NET WebForms with ViewState and AutoPostBack dropdowns.
 * Flow: GET page → select Region (POST) → select Course (POST) → read batch table
 */
class ICAIScraper {

    companion object {
        const val BASE_URL = "https://www.icaionlineregistration.org/LaunchBatchDetail.aspx"
    }

    // OkHttp client with reasonable timeouts
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .addInterceptor { chain ->
            // Add browser-like headers so the server doesn't block us
            val request = chain.request().newBuilder()
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120.0.0.0 Safari/537.36")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.5")
                .header("Referer", BASE_URL)
                .build()
            chain.proceed(request)
        }
        .build()

    // ─── Data classes ───────────────────────────────────────────────────────────

    /** Hidden ASP.NET form fields required for every POST request */
    data class FormFields(
        val viewState: String = "",
        val viewStateGenerator: String = "",
        val eventValidation: String = ""
    )

    /** A dropdown option: value sent in POST + label shown to user */
    data class DropdownOption(val value: String, val label: String) {
        override fun toString() = label
    }

    /** A single batch row from the results table */
    data class BatchInfo(
        val batchNo: String,
        val startDate: String,
        val endDate: String,
        val venue: String,
        val availableSeats: String,
        val status: String,
        val rawColumns: List<String> = emptyList()    // fallback: keep all columns
    ) {
        /** Unique key used to detect new/changed batches */
        fun uniqueKey() = listOf(batchNo, startDate, endDate, venue)
            .filter { it.isNotBlank() }
            .joinToString("|")

        /** True if this batch seems open/has seats */
        fun looksAvailable(): Boolean {
            val seatsNum = availableSeats.trim().toIntOrNull()
            return when {
                seatsNum != null -> seatsNum > 0
                status.contains("open", ignoreCase = true) -> true
                status.contains("available", ignoreCase = true) -> true
                else -> false
            }
        }
    }

    // ─── Helpers ────────────────────────────────────────────────────────────────

    /** Extract ASP.NET hidden form fields from a Jsoup Document */
    private fun extractFormFields(doc: Document): FormFields {
        return FormFields(
            viewState = doc.select("input[name=__VIEWSTATE]").attr("value"),
            viewStateGenerator = doc.select("input[name=__VIEWSTATEGENERATOR]").attr("value"),
            eventValidation = doc.select("input[name=__EVENTVALIDATION]").attr("value")
        )
    }

    /** Build a FormBody for an ASP.NET postback */
    private fun buildPostBody(
        fields: FormFields,
        eventTarget: String = "",
        region: String = "",
        pou: String = "",
        course: String = ""
    ): FormBody = FormBody.Builder()
        .add("__EVENTTARGET", eventTarget)
        .add("__EVENTARGUMENT", "")
        .add("__LASTFOCUS", "")
        .add("__VIEWSTATE", fields.viewState)
        .add("__VIEWSTATEGENERATOR", fields.viewStateGenerator)
        .add("__EVENTVALIDATION", fields.eventValidation)
        .add("ddl_reg", region)
        .add("ddlPou", pou)
        .add("ddl_course", course)
        .build()

    /** Fetch a URL and return the parsed Document */
    private fun fetch(url: String): Document {
        val req = Request.Builder().url(url).get().build()
        val html = client.newCall(req).execute().use { resp ->
            resp.body?.string() ?: throw Exception("Empty response from $url")
        }
        return Jsoup.parse(html, url)
    }

    /** POST a form and return the parsed Document */
    private fun post(fields: FormFields, eventTarget: String, region: String, pou: String, course: String): Document {
        val body = buildPostBody(fields, eventTarget, region, pou, course)
        val req = Request.Builder().url(BASE_URL).post(body).build()
        val html = client.newCall(req).execute().use { resp ->
            resp.body?.string() ?: throw Exception("Empty POST response")
        }
        return Jsoup.parse(html, BASE_URL)
    }

    // ─── Public API ─────────────────────────────────────────────────────────────

    /**
     * Step 1: Load the page and get the list of regions.
     * Returns (FormFields for next step, list of region options)
     */
    fun getRegions(): Pair<FormFields, List<DropdownOption>> {
        val doc = fetch(BASE_URL)
        val fields = extractFormFields(doc)
        val regions = doc.select("#ddlRegion option, select[name=ddlRegion] option")
            .toList().filter { it.attr("value").isNotBlank() && it.attr("value") != "0" }
            .map { DropdownOption(it.attr("value"), it.text().trim()) }
        return Pair(fields, regions)
    }

    /**
     * Step 2: Post the selected region to get the POU list.
     * Returns (updated FormFields, list of POU options)
     */
    fun getPOUs(fields: FormFields, regionValue: String): Pair<FormFields, List<DropdownOption>> {
        val doc = post(fields, "ddl_reg", regionValue, "", "")
        val newFields = extractFormFields(doc)
        val pous = doc.select("#ddlPou option, select[name=ddlPou] option")
            .toList().filter { it.attr("value").isNotBlank() && it.attr("value") != "0" }
            .map { DropdownOption(it.attr("value"), it.text().trim()) }
        return Pair(newFields, pous)
    }

    /**
     * Step 3: Get courses available (these are static, already in the HTML).
     * Still returns FormFields after a POU postback for completeness.
     */
    fun getCourses(fields: FormFields, regionValue: String, pouValue: String): Pair<FormFields, List<DropdownOption>> {
        val doc = post(fields, "ddlPou", regionValue, pouValue, "")
        val newFields = extractFormFields(doc)
        val courses = doc.select("#ddl_course option, select[name=ddl_course] option")
            .toList().filter { it.attr("value").isNotBlank() && it.attr("value") != "0" }
            .map { DropdownOption(it.attr("value"), it.text().trim()) }
        return Pair(newFields, courses)
    }

    /**
     * Step 4: Fetch the batch list for Region + POU + Course.
     * This is called both from the UI (preview) and from WorkManager (monitoring).
     */
    fun getBatches(
        region: String,
        pou: String,
        course: String
    ): List<BatchInfo> {
        // We always do the full 3-step flow to get fresh ViewState
        val (fields1, _) = getRegions()
        val (fields2, _) = getPOUs(fields1, region)
        val (fields3, _) = getCourses(fields2, region, pou)

        // Final POST: trigger the Course dropdown which loads the grid
        val doc = post(fields3, "ddlCourse", region, pou, course)

        return parseBatches(doc)
    }

    /** Parse batch rows from the result page */
    private fun parseBatches(doc: Document): List<BatchInfo> {
        val batches = mutableListOf<BatchInfo>()

        // ICAI uses a GridView which renders as an HTML table.
        // Try several selectors to be robust against minor HTML changes.
        val table = doc.select(
            "table[id*=Grid], table[id*=grid], table.gridView, " +
            "table[class*=Grid], table[class*=grid], " +
            ".gridContainer table, #ContentPlaceHolder1_gvBatch, " +
            "table" // fallback: first table with enough columns
        ).firstOrNull { tbl ->
            val headers = tbl.select("tr:first-child th, tr:first-child td")
            headers.size >= 3  // batch tables have at least 3 columns
        } ?: return emptyList()

        val rows = table.select("tr")
        // Skip header row (index 0)
        rows.drop(1).forEach { row ->
            val cells = row.select("td")
            if (cells.size < 3) return@forEach  // skip empty/malformed rows

            val rawCols = cells.map { it.text().trim() }

            // Map columns generically — the exact order can vary by ICAI update
            batches.add(BatchInfo(
                batchNo      = rawCols.getOrElse(0) { "" },
                startDate    = rawCols.getOrElse(1) { "" },
                endDate      = rawCols.getOrElse(2) { "" },
                venue        = rawCols.getOrElse(3) { "" },
                availableSeats = rawCols.getOrElse(4) { "" },
                status       = rawCols.getOrElse(5) { "" },
                rawColumns   = rawCols
            ))
        }

        return batches
    }
}
}
}
