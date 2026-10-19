package com.example.timecard_robokon2026

import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.FileReader
import java.io.OutputStreamWriter
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

class MainActivity : AppCompatActivity() {

    private lateinit var database: AttendanceDatabase
    private lateinit var dao: AttendanceDao

    // デジタル時計（秒表示）
    private val clockHandler = Handler(Looper.getMainLooper())
    private lateinit var tvDigitalClock: TextView
    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
    private val clockRunnable = object : Runnable {
        override fun run() {
            if (!isFinishing && !isDestroyed) {
                tvDigitalClock.text = LocalTime.now().format(timeFormatter)
                clockHandler.postDelayed(this, 1000)
            }
        }
    }

    // IDとユーザー名の対応表
    private var userMasterMap = mutableMapOf<String, String>()

    // 同期フォルダ選択ランチャー（端末ローカルストレージ用）
    private val requestSyncFolderLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
                getSharedPreferences("timecard_prefs", MODE_PRIVATE).edit {
                    putString("sync_folder_uri", uri.toString())
                }
                showToast("端末ローカル同期フォルダを設定しました。")
                lifecycleScope.launch { syncLogsToPublicExcelCsv() }
            } catch (e: Exception) {
                e.printStackTrace()
                showToast("同期フォルダの設定に失敗しました。")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // データベース初期化
        database = AttendanceDatabase.getDatabase(this)
        dao = database.attendanceDao()
        loadUserMasterFromFile() // 起動時に外部ファイルから名簿を読み込む

        // UIコンポーネント紐づけ
        tvDigitalClock = findViewById(R.id.tv_digital_clock)
        val etUserId = findViewById<EditText>(R.id.et_user_id)
        val btnIn1 = findViewById<Button>(R.id.btn_in_1)
        val btnIn2 = findViewById<Button>(R.id.btn_in_2)
        val btnIn3 = findViewById<Button>(R.id.btn_in_3)
        val btnOut = findViewById<Button>(R.id.btn_out)
        val btnRestIn = findViewById<Button>(R.id.btn_rest_in)
        val btnRestOut = findViewById<Button>(R.id.btn_rest_out)
        val btnExport = findViewById<Button>(R.id.btn_export)
        val btnReloadMaster = findViewById<Button>(R.id.btn_reload_master)
        val btnOpenDrive = findViewById<Button>(R.id.btn_open_drive)

        // 共通打刻処理
        fun recordAction(actionType: String) {
            val userId = etUserId.text.toString().trim()
            // IDチェック
            if ((userId.length != 5) || !userId.all { it.isDigit() }) {
                showToast("ユーザーID(学籍番号)は5桁の数字で入力してください(例:24500)")
                return
            }

            val userName = getUserName(userId)

            lifecycleScope.launch {
                val today = LocalDate.now().toString()
                val nowTime = LocalTime.now().format(timeFormatter)
                val log = AttendanceLog(userId = userId, date = today, time = nowTime, actionType = actionType)
                dao.insertLog(log)
                showToast("Recorded【$actionType】$userName ($userId).")
                etUserId.text.clear()

                // 打刻時に自動保存（Documents/Robokon_Timecard/ および 設定済みフォルダ）
                syncLogsToPublicExcelCsv()
            }
        }

        // ボタンイベント
        btnIn1.setOnClickListener { recordAction("出勤1(加工員)") }
        btnIn2.setOnClickListener { recordAction("出勤2(会計員)") }
        btnIn3.setOnClickListener { recordAction("出勤3(ロボット説明員)") }
        btnOut.setOnClickListener { recordAction("退勤") }
        btnRestIn.setOnClickListener { recordAction("休憩IN") }
        btnRestOut.setOnClickListener { recordAction("休憩OUT") }
        btnExport.setOnClickListener { showExportDialog() }
        btnReloadMaster.setOnClickListener { loadUserMasterFromFile() }
        btnOpenDrive.setOnClickListener { saveCurrentLogsToGoogleDriveNative() }

        // 起動時に自動で公開Documentsフォルダへ同期
        lifecycleScope.launch {
            syncLogsToPublicExcelCsv()
        }
    }

    private suspend fun syncLogsToPublicExcelCsv() = withContext(Dispatchers.IO) {
        try {
            val allRecords = dao.getAllLogsOnce()

            val csvContentBuilder = StringBuilder().apply {
                append("ID,ユーザーID,ユーザー名,日付,時刻,アクション\n")
                for (item in allRecords) {
                    val userName = userMasterMap[item.userId] ?: "未登録ユーザー"
                    append("${item.id},${item.userId},$userName,${item.date},${item.time},${item.actionType}\n")
                }
            }
            val csvString = csvContentBuilder.toString()

            // 1. SZJ-JS202 内部ストレージ Documents フォルダ (/storage/emulated/0/Documents/Robokon_Timecard/)
            val docsDir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
                "Robokon_Timecard",
            )
            if (!docsDir.exists()) {
                docsDir.mkdirs()
            }
            val docsFile = File(docsDir, "attendance_fixed_sync.csv")
            writeCsvToFile(docsFile, csvString)

            // 2. SZJ-JS202 内部ストレージ Download フォルダ (/storage/emulated/0/Download/Robokon_Timecard/)
            val downloadsDir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "Robokon_Timecard",
            )
            if (!downloadsDir.exists()) {
                downloadsDir.mkdirs()
            }
            val downloadsFile = File(downloadsDir, "attendance_fixed_sync.csv")
            writeCsvToFile(downloadsFile, csvString)

            // Android MediaScanner を起動して、SZJ-JS202 のファイルブラウザに即座に表示させる
            MediaScannerConnection.scanFile(
                this@MainActivity,
                arrayOf(docsFile.absolutePath, downloadsFile.absolutePath),
                arrayOf("text/csv"),
                null,
            )

            // 3. SAF で設定されたカスタムフォルダへ出力
            val prefs = getSharedPreferences("timecard_prefs", MODE_PRIVATE)
            val folderUriStr = prefs.getString("sync_folder_uri", null)
            if (folderUriStr != null) {
                val folderUri = folderUriStr.toUri()
                val documentDir = DocumentFile.fromTreeUri(this@MainActivity, folderUri)
                if ((documentDir != null) && documentDir.canWrite()) {
                    val targetFile = documentDir.findFile("attendance_fixed_sync.csv")
                        ?: documentDir.createFile("text/csv", "attendance_fixed_sync.csv")
                    targetFile?.let { file ->
                        contentResolver.openOutputStream(file.uri, "rwt")?.use { os ->
                            OutputStreamWriter(os, "Shift_JIS").use { writer ->
                                writer.write(csvString)
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun writeCsvToFile(file: File, csvString: String) {
        OutputStreamWriter(FileOutputStream(file), "Shift_JIS").use { writer ->
            writer.write(csvString)
        }
    }

    private fun saveCurrentLogsToGoogleDriveNative() {
        lifecycleScope.launch {
            val allRecords = dao.getAllLogsOnce()
            if (allRecords.isEmpty()) {
                showToast("ログが存在しません。")
                return@launch
            }
            exportDatabaseToCsv(this@MainActivity, allRecords, userMasterMap)
        }
    }

    // 外部テキストファイルからID名簿をロード
    private fun loadUserMasterFromFile() {
        try {
            val dir = getExternalFilesDir(null) ?: filesDir
            if (!dir.exists()) {
                dir.mkdirs()
            }
            val masterFile = File(dir, "user_master.txt")
            if (!masterFile.exists()) {
                masterFile.createNewFile()
                masterFile.writeText("24500,Sample000\n25000,Sample001\n")
                showToast("user_master.txt を新規作成しました")
            }
            val newMap = mutableMapOf<String, String>()
            BufferedReader(FileReader(masterFile)).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    val trimmed = line.trim()
                    if (trimmed.isEmpty()) continue
                    val tokens = trimmed.split(",")
                    if (tokens.size >= 2) {
                        newMap[tokens[0].trim()] = tokens[1].trim()
                    }
                }
            }
            userMasterMap = newMap
            showToast("Loaded master.txt（${userMasterMap.size}名）")
        } catch (e: Exception) {
            e.printStackTrace()
            showToast("Failed to load Master.")
        }
    }

    private fun getUserName(userId: String): String = userMasterMap[userId] ?: "未登録ユーザー。PLZ contact observer."

    private fun showExportDialog() {
        val options = arrayOf("全期間", "直近1時間", "直近1日", "直近1週間", "直近1ヶ月", "任意日数指定", "端末ローカル同期フォルダの設定")
        var selectedIndex = 0

        AlertDialog.Builder(this)
            .setTitle("CSVエクスポート・同期設定")
            .setSingleChoiceItems(options, selectedIndex) { _, which ->
                selectedIndex = which
            }
            .setPositiveButton("選択") { _, _ ->
                when (val selectedOption = options[selectedIndex]) {
                    "任意日数指定" -> showCustomDaysDialog()
                    "端末ローカル同期フォルダの設定" -> requestSyncFolderLauncher.launch(null)
                    else -> exportAttendanceLogWithFilter(selectedOption, null)
                }
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun showCustomDaysDialog() {
        val input = EditText(this).apply {
            hint = "日数（例: 30）"
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        AlertDialog.Builder(this)
            .setTitle("任意日数の指定")
            .setMessage("過去何日分のログを出力しますか？")
            .setView(input)
            .setPositiveButton("出力") { _, _ ->
                val daysStr = input.text.toString().trim()
                val days = daysStr.toIntOrNull()
                if ((days != null) && (days > 0)) {
                    exportAttendanceLogWithFilter("任意日数指定", days)
                } else {
                    showToast("有効な日数を入力してください。")
                }
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun exportAttendanceLogWithFilter(filterType: String, customDays: Int?) {
        lifecycleScope.launch {
            val allRecords = dao.getAllLogsOnce()
            if (allRecords.isEmpty()) {
                showToast("ログが存在しません。")
                return@launch
            }
            val filteredRecords = filterLogs(allRecords, filterType, customDays)
            if (filteredRecords.isEmpty()) {
                showToast("指定された期間のログが存在しません。")
                return@launch
            }
            exportDatabaseToCsv(this@MainActivity, filteredRecords, userMasterMap)
        }
    }

    private fun filterLogs(
        logs: List<AttendanceLog>,
        filterType: String,
        customDays: Int?,
    ): List<AttendanceLog> {
        if (filterType == "全期間") return logs

        val now = LocalDateTime.now()
        val cutoff = when (filterType) {
            "直近1時間" -> now.minusHours(1)
            "直近1日" -> now.minusDays(1)
            "直近1週間" -> now.minusWeeks(1)
            "直近1ヶ月" -> now.minusMonths(1)
            "任意日数指定" -> customDays?.let { now.minusDays(it.toLong()) }
            else -> null
        } ?: return logs

        val dateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")
        return logs.filter { log ->
            try {
                val logDateTime = LocalDateTime.parse("${log.date}T${log.time}", dateTimeFormatter)
                !logDateTime.isBefore(cutoff)
            } catch (_: Exception) {
                try {
                    val logDate = LocalDate.parse(log.date)
                    !logDate.isBefore(cutoff.toLocalDate())
                } catch (_: Exception) {
                    true
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        clockHandler.post(clockRunnable)
    }

    override fun onPause() {
        super.onPause()
        clockHandler.removeCallbacks(clockRunnable)
    }

    private fun showToast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}

fun exportDatabaseToCsv(
    context: Context,
    attendances: List<AttendanceLog>,
    userMasterMap: Map<String, String> = emptyMap(),
) {
    try {
        val cacheDir = File(context.cacheDir, "shared_files")
        if (!cacheDir.exists()) {
            cacheDir.mkdirs()
        }
        val csvFile = File(cacheDir, "timecard_log_${System.currentTimeMillis()}.csv")
        OutputStreamWriter(FileOutputStream(csvFile), "Shift_JIS").use { writer ->
            writer.write("ID,ユーザーID,ユーザー名,日付,時刻,アクション\n")
            for (item in attendances) {
                val userName = userMasterMap[item.userId] ?: "未登録ユーザー"
                writer.write("${item.id},${item.userId},$userName,$item.date,$item.time,${item.actionType}\n")
            }
        }

        try {
            val baseDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
            val exportDir = File(baseDir, "Robokon_Timecard")
            if (!exportDir.exists()) {
                exportDir.mkdirs()
            }
            val publicFile = File(exportDir, "attendance_fixed_sync.csv")
            csvFile.copyTo(publicFile, overwrite = true)

            MediaScannerConnection.scanFile(
                context,
                arrayOf(publicFile.absolutePath),
                arrayOf("text/csv"),
                null,
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }

        val contentUri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            csvFile,
        )

        // Directly target Google Drive native app's "Save to Drive" activity if installed
        val driveIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, contentUri)
            putExtra(Intent.EXTRA_SUBJECT, "タイムカードログ (Robokon)")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            setPackage("com.google.android.apps.docs")
        }

        try {
            context.startActivity(driveIntent)
        } catch (_: Exception) {
            // Fallback to standard chooser if Drive app package is not explicitly bound
            val shareIntent = Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/csv"
                    putExtra(Intent.EXTRA_STREAM, contentUri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                },
                "Google Drive に保存",
            )
            context.startActivity(shareIntent)
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
}
