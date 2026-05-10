package com.sohil.icaibatchmonitor

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    // ─── Views ──────────────────────────────────────────────────────────────────
    private lateinit var spinnerRegion: AutoCompleteTextView
    private lateinit var spinnerPou: AutoCompleteTextView
    private lateinit var spinnerCourse: AutoCompleteTextView
    private lateinit var spinnerInterval: AutoCompleteTextView
    private lateinit var btnAdd: MaterialButton
    private lateinit var btnToggleMonitor: MaterialButton
    private lateinit var btnRefreshNow: MaterialButton
    private lateinit var tvStatus: TextView
    private lateinit var tvConfigHeader: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var recyclerView: RecyclerView
    private lateinit var layoutForm: View

    // ─── State ──────────────────────────────────────────────────────────────────
    private lateinit var prefs: PreferencesManager
    private lateinit var adapter: MonitoredConfigAdapter
    private val scraper = ICAIScraper()

    // Dropdown option lists
    private var regionOptions = listOf<ICAIScraper.DropdownOption>()
    private var pouOptions = listOf<ICAIScraper.DropdownOption>()
    private var courseOptions = listOf<ICAIScraper.DropdownOption>()

    // Stored form fields from last ICAI page fetch
    private var formFields: ICAIScraper.FormFields? = null

    // Check interval options (label -> minutes)
    private val intervalOptions = listOf(
        "Every 15 minutes" to 15,
        "Every 30 minutes" to 30,
        "Every 1 hour" to 60,
        "Every 2 hours" to 120,
        "Every 6 hours" to 360
    )

    // Notification permission launcher (Android 13+)
    private val notifPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                showSnack("Notification permission denied. You won't get alerts.")
            }
        }

    // ─── Lifecycle ──────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = PreferencesManager(this)
        NotificationHelper.createNotificationChannel(this)
        requestNotificationPermission()

        bindViews()
        setupRecyclerView()
        setupIntervalSpinner()
        setupMonitorButton()
        setupRefreshButton()
        setupAddButton()

        // Load regions on startup
        loadRegions()
        refreshConfigList()
        updateMonitorButtonState()
    }

    override fun onResume() {
        super.onResume()
        refreshConfigList()
        updateMonitorButtonState()
    }

    // ─── View Setup ─────────────────────────────────────────────────────────────

    private fun bindViews() {
        spinnerRegion = findViewById(R.id.spinnerRegion)
        spinnerPou = findViewById(R.id.spinnerPou)
        spinnerCourse = findViewById(R.id.spinnerCourse)
        spinnerInterval = findViewById(R.id.spinnerInterval)
        btnAdd = findViewById(R.id.btnAdd)
        btnToggleMonitor = findViewById(R.id.btnToggleMonitor)
        btnRefreshNow = findViewById(R.id.btnRefreshNow)
        tvStatus = findViewById(R.id.tvStatus)
        tvConfigHeader = findViewById(R.id.tvConfigHeader)
        progressBar = findViewById(R.id.progressBar)
        recyclerView = findViewById(R.id.recyclerView)
        layoutForm = findViewById(R.id.layoutForm)
    }

    private fun setupRecyclerView() {
        adapter = MonitoredConfigAdapter(onDelete = { config ->
            AlertDialog.Builder(this)
                .setTitle("Remove Monitor")
                .setMessage("Stop monitoring ${config.displayName()}?")
                .setPositiveButton("Remove") { _, _ ->
                    prefs.removeConfig(config.id)
                    refreshConfigList()
                    showSnack("Removed: ${config.courseLabel}")
                }
                .setNegativeButton("Cancel", null)
                .show()
        })
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter
    }

    private fun setupIntervalSpinner() {
        val labels = intervalOptions.map { it.first }
        val intervalAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels)
        intervalAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerInterval.adapter = intervalAdapter
        spinnerInterval.setSelection(1) // default: 30 min
    }

    // ─── Region/POU/Course Loading ──────────────────────────────────────────────

    private fun loadRegions() {
        setLoading(true, "Loading regions from ICAI website...")

        lifecycleScope.launch {
            try {
                val (fields, regions) = withContext(Dispatchers.IO) {
                    scraper.getRegions()
                }
                formFields = fields
                regionOptions = regions

                if (regions.isEmpty()) {
                    setStatus("⚠️ Could not load regions. Check your internet connection.")
                    setLoading(false)
                    return@launch
                }

                val labels = regions.map { it.label }
                val regionAdapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_dropdown_item_1line, labels)
                spinnerRegion.setAdapter(regionAdapter)

                spinnerRegion.setOnItemClickListener { _, _, pos, _ ->
                    clearSpinner(spinnerPou, "")
                    clearSpinner(spinnerCourse, "")
                    loadPOUs(regions[pos])
                }

                setStatus("✅ Loaded ${regions.size} regions. Select one to continue.")
                setLoading(false)

            } catch (e: Exception) {
                setStatus("❌ Error loading regions: ${e.message}")
                setLoading(false)
            }
        }
    }

    private fun loadPOUs(region: ICAIScraper.DropdownOption) {
        val currentFields = formFields ?: return
        setLoading(true, "Loading POUs for ${region.label}...")
        clearSpinner(spinnerPou, "Loading...")
        clearSpinner(spinnerCourse, "Select Course")

        lifecycleScope.launch {
            try {
                val (newFields, pous) = withContext(Dispatchers.IO) {
                    scraper.getPOUs(currentFields, region.value)
                }
                formFields = newFields
                pouOptions = pous

                if (pous.isEmpty()) {
                    clearSpinner(spinnerPou, "No POUs found")
                    setStatus("No POUs found for ${region.label}")
                    setLoading(false)
                    return@launch
                }

                val labels = listOf("Select POU") + pous.map { it.label }
                val pouAdapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_item, labels)
                pouAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                spinnerPou.adapter = pouAdapter

                spinnerPou.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(parent: AdapterView<*>, view: View?, pos: Int, id: Long) {
                        if (pos == 0) {
                            clearSpinner(spinnerCourse, "Select Course")
                            return
                        }
                        loadCourses(region, pous[pos - 1])
                    }
                    override fun onNothingSelected(parent: AdapterView<*>) {}
                }

                setStatus("✅ Loaded ${pous.size} POUs. Select one.")
                setLoading(false)

            } catch (e: Exception) {
                setStatus("❌ Error loading POUs: ${e.message}")
                setLoading(false)
            }
        }
    }

    private fun loadCourses(
        region: ICAIScraper.DropdownOption,
        pou: ICAIScraper.DropdownOption
    ) {
        val currentFields = formFields ?: return
        setLoading(true, "Loading courses...")

        lifecycleScope.launch {
            try {
                val (newFields, courses) = withContext(Dispatchers.IO) {
                    scraper.getCourses(currentFields, region.value, pou.value)
                }
                formFields = newFields
                courseOptions = courses

                val labels = if (courses.isEmpty()) {
                    listOf("ICITSS - Orientation Course",
                           "ICITSS - Information Technology",
                           "AICITSS - Advanced Information Technology",
                           "Advanced (ICITSS) MCS Course",
                           "Advanced (ICITSS) MCS Course - Weekend")
                } else {
                    listOf("Select Course") + courses.map { it.label }
                }

                val courseAdapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_item, labels)
                courseAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                spinnerCourse.adapter = courseAdapter

                setStatus("✅ Ready. Select a course and tap Add Monitor.")
                setLoading(false)

            } catch (e: Exception) {
                // Even if course fetch fails, show static list (courses rarely change)
                val staticCourses = listOf(
                    "ICITSS - Orientation Course",
                    "ICITSS - Information Technology",
                    "AICITSS - Advanced Information Technology",
                    "Advanced (ICITSS) MCS Course",
                    "Advanced (ICITSS) MCS Course - Weekend"
                )
                val courseAdapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_dropdown_item_1line, staticCourses)
                spinnerCourse.setAdapter(courseAdapter)
                setStatus("⚠️ Using default course list. Select course and proceed.")
                setLoading(false)
            }
        }
    }

    // ─── Add Config ─────────────────────────────────────────────────────────────

    private fun setupAddButton() {
        btnAdd.setOnClickListener {
            val regionLabel = spinnerRegion.text.toString()
            val pouLabel = spinnerPou.text.toString()
            val courseLabel = spinnerCourse.text.toString()
            val intervalLabel = spinnerInterval.text.toString()

            val region = regionOptions.find { it.label == regionLabel }
            val pou = pouOptions.find { it.label == pouLabel }
            
            if (region == null) {
                showSnack("Please select a valid region")
                return@setOnClickListener
            }
            if (pou == null) {
                showSnack("Please select a valid POU")
                return@setOnClickListener
            }
            if (courseLabel.isBlank()) {
                showSnack("Please select a course")
                return@setOnClickListener
            }

            val intervalMinutes = intervalOptions.find { it.first == intervalLabel }?.second ?: 30

            // For course, either use fetched options or spinner text
            val courseValue = courseOptions.find { it.label == courseLabel }?.value ?: courseLabel

            val config = MonitorConfig(
                regionLabel = region.label,
                pouLabel = pou.label,
                courseLabel = courseLabel,
                regionValue = region.value,
                pouValue = pou.value,
                courseValue = courseValue,
                intervalMinutes = intervalMinutes
            )

            val added = prefs.addConfig(config)
            if (added) {
                showSnack("✅ Added: ${config.courseLabel} @ ${config.pouLabel}")
                refreshConfigList()
            } else {
                showSnack("⚠️ This combination is already being monitored")
            }
        }
    }

    // ─── Monitor Toggle ─────────────────────────────────────────────────────────

    private fun setupMonitorButton() {
        btnToggleMonitor.setOnClickListener {
            val configs = prefs.getConfigs()
            if (configs.isEmpty()) {
                showSnack("Add at least one batch to monitor first")
                return@setOnClickListener
            }

            val isActive = prefs.isMonitoringActive()
            if (isActive) {
                // Stop monitoring
                BatchMonitorWorker.cancel(this)
                prefs.setMonitoringActive(false)
                updateMonitorButtonState()
                showSnack("🛑 Monitoring stopped")
            } else {
                // Start monitoring — use the smallest interval among all configs
                val minInterval = configs.minOf { it.intervalMinutes }.toLong()
                BatchMonitorWorker.schedule(this, minInterval)
                prefs.setMonitoringActive(true)
                updateMonitorButtonState()
                showSnack("▶️ Monitoring started (every $minInterval min)")
            }
        }
    }

    private fun setupRefreshButton() {
        btnRefreshNow.setOnClickListener {
            val configs = prefs.getConfigs()
            if (configs.isEmpty()) {
                showSnack("Add at least one config first")
                return@setOnClickListener
            }
            BatchMonitorWorker.runNow(this)
            showSnack("🔄 Manual check triggered — you'll get a notification if batches are found")
        }
    }

    private fun updateMonitorButtonState() {
        val isActive = prefs.isMonitoringActive()
        if (isActive) {
            btnToggleMonitor.text = "⏹ Stop Monitoring"
            btnToggleMonitor.setBackgroundColor(getColor(android.R.color.holo_red_light))
            tvStatus.text = "🟢 Monitoring is ACTIVE — you'll get notified when batches open"
        } else {
            btnToggleMonitor.text = "▶ Start Monitoring"
            btnToggleMonitor.setBackgroundColor(getColor(android.R.color.holo_green_dark))
            tvStatus.text = "⏸ Monitoring is paused"
        }
    }

    // ─── Helpers ────────────────────────────────────────────────────────────────

    private fun refreshConfigList() {
        val configs = prefs.getConfigs()
        adapter.submitList(configs)
        tvConfigHeader.text = "Monitored Batches (${configs.size})"
    }

    private fun clearSpinner(spinner: Spinner, placeholder: String) {
        val placeholderAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, listOf(placeholder))
        placeholderAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = placeholderAdapter
    }

    private fun setLoading(loading: Boolean, message: String = "") {
        progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        btnAdd.isEnabled = !loading
        if (message.isNotBlank()) tvStatus.text = message
    }

    private fun setStatus(message: String) {
        tvStatus.text = message
    }

    private fun showSnack(message: String) {
        Snackbar.make(recyclerView, message, Snackbar.LENGTH_LONG).show()
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}
 }
    }
}
