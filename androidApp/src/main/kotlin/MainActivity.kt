package com.example.timecard_robokon2026

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.InputType
import android.util.Log
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
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.FileReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

private const val DEFAULT_WEBHOOK_URL = "https://script.google.com/macros/s/AKfycbzzzc09Q_UyKYrkl2HfhCVHp5--yoAvjlyQFsXGiNYArfAq_uxyvg-DPCpQYGk5jHF5/exec"

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
    private var userMasterMap = mutableMapOf<String, String>()

    // 同期フォルダ選択ランチャー（端末ローカルストレージ用）
    private val requestSyncFolderLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
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

        // Android 9 以前のストレージ権限リクエスト
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(
                    arrayOf(
                        Manifest.permission.READ_EXTERNAL_STORAGE,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    ),
                    1001,
                )
            }
        }

        database = AttendanceDatabase.getDatabase(this)
        dao = database.attendanceDao()
        loadUserMasterFromFile()

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

        // 打刻処理
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
                // 打刻時に自動保存（Documents/Robokon_Timecard/ および Google Drive Webhook/API）
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
        btnOpenDrive.setOnClickListener { handleGoogleDriveOptions() }
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

            // 1. MediaStore を使用した公開 Documents フォルダ保存 (Android 10+ EACCES 回避)
            saveToPublicDocumentsMediaStore(this@MainActivity, "Robokon_TimecardLog.csv", csvString)
            saveToPublicDocumentsMediaStore(this@MainActivity, "attendance_fixed_sync.csv", csvString)

            // 2. アプリ専用 Documents フォルダへ保存
            saveToAppExternalDocumentsDir(this@MainActivity, "Robokon_TimecardLog.csv", csvString)
            saveToAppExternalDocumentsDir(this@MainActivity, "attendance_fixed_sync.csv", csvString)

            // 3. SAF で設定されたカスタムフォルダへ出力
            val prefs = getSharedPreferences("timecard_prefs", MODE_PRIVATE)
            val folderUriStr = prefs.getString("sync_folder_uri", null)
            if (folderUriStr != null) {
                val folderUri = folderUriStr.toUri()
                val documentDir = DocumentFile.fromTreeUri(this@MainActivity, folderUri)
                if ((documentDir != null) && documentDir.canWrite()) {
                    val targetFile1 = documentDir.findFile("Robokon_TimecardLog.csv") ?: documentDir.createFile("text/csv", "Robokon_TimecardLog.csv")
                    targetFile1?.let { file ->
                        contentResolver.openOutputStream(file.uri, "rwt")?.use { os ->
                            OutputStreamWriter(os, "Shift_JIS").use { writer ->
                                writer.write(csvString)
                            }
                        }
                    }
                    val targetFile2 = documentDir.findFile("attendance_fixed_sync.csv") ?: documentDir.createFile("text/csv", "attendance_fixed_sync.csv")
                    targetFile2?.let { file ->
                        contentResolver.openOutputStream(file.uri, "rwt")?.use { os ->
                            OutputStreamWriter(os, "Shift_JIS").use { writer ->
                                writer.write(csvString)
                            }
                        }
                    }
                }
            }

            // 4. Google Drive REST API / Webhook 自動送信
            uploadToGoogleDriveApi(folderId = "1NlTc2YswYVFXxLSvD08PYjuc1Ev1UOgz", filename = "Robokon_TimecardLog.csv", csvData = csvString)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun saveToPublicDocumentsMediaStore(context: Context, filename: String, csvString: String) {
        val subFolder = "Robokon_Timecard"
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/csv")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOCUMENTS}/$subFolder")
                }

                try {
                    val selection = "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?"
                    val selectionArgs = arrayOf(filename, "${Environment.DIRECTORY_DOCUMENTS}/$subFolder/")
                    resolver.delete(MediaStore.Files.getContentUri("external"), selection, selectionArgs)
                } catch (_: Exception) {}

                val uri = resolver.insert(MediaStore.Files.getContentUri("external"), contentValues)
                if (uri != null) {
                    resolver.openOutputStream(uri, "rwt")?.use { os ->
                        OutputStreamWriter(os, "Shift_JIS").use { writer ->
                            writer.write(csvString)
                        }
                    }
                    return
                }
            }

            // Android 9 以前（安全なフォルダ作成・書き込み）
            val baseDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
            val exportDir = File(baseDir, subFolder)
            if (!exportDir.exists()) {
                exportDir.mkdirs()
            }
            if (exportDir.exists() && exportDir.canWrite()) {
                val file = File(exportDir, filename)
                OutputStreamWriter(FileOutputStream(file), "Shift_JIS").use { writer ->
                    writer.write(csvString)
                }
                MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf("text/csv"), null)
            } else {
                saveToAppExternalDocumentsDir(context, filename, csvString)
            }
        } catch (_: Exception) {
            saveToAppExternalDocumentsDir(context, filename, csvString)
        }
    }

    private fun saveToAppExternalDocumentsDir(context: Context, filename: String, csvString: String) {
        try {
            val appDocsDir = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
            if (appDocsDir != null) {
                if (!appDocsDir.exists()) {
                    appDocsDir.mkdirs()
                }
                val file = File(appDocsDir, filename)
                OutputStreamWriter(FileOutputStream(file), "Shift_JIS").use { writer ->
                    writer.write(csvString)
                }
                MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf("text/csv"), null)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private suspend fun uploadToGoogleDriveApi(folderId: String, filename: String, csvData: String) = withContext(Dispatchers.IO) {
        val prefs = getSharedPreferences("timecard_prefs", MODE_PRIVATE)
        val accessToken = prefs.getString("google_drive_access_token", null)
        if (!accessToken.isNullOrEmpty()) {
            uploadFileViaDriveRestApi(folderId, filename, csvData, accessToken)
        }

        val webhookUrl = prefs.getString("cloud_webhook_url", DEFAULT_WEBHOOK_URL) ?: DEFAULT_WEBHOOK_URL
        uploadCsvToCloudWebhook(webhookUrl, folderId, filename, csvData)
    }

    private fun uploadFileViaDriveRestApi(folderId: String, filename: String, csvData: String, accessToken: String) {
        try {
            val encodedQuery = URLEncoder.encode("name = '$filename' and '$folderId' in parents and trashed = false", "UTF-8")
            val searchUrl = URL("https://www.googleapis.com/drive/v3/files?q=$encodedQuery")
            val searchConn = searchUrl.openConnection() as HttpURLConnection
            searchConn.requestMethod = "GET"
            searchConn.setRequestProperty("Authorization", "Bearer $accessToken")
            var existingFileId: String? = null
            if (searchConn.responseCode == 200) {
                val response = searchConn.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(response)
                val filesArray = json.optJSONArray("files")
                if ((filesArray != null) && (filesArray.length() > 0)) {
                    existingFileId = filesArray.getJSONObject(0).getString("id")
                }
            }
            searchConn.disconnect()
            val bytes = csvData.toByteArray(charset("Shift_JIS"))
            if (existingFileId != null) {
                val updateUrl = URL("https://www.googleapis.com/upload/drive/v3/files/$existingFileId?uploadType=media")
                val updateConn = updateUrl.openConnection() as HttpURLConnection
                updateConn.requestMethod = "PATCH"
                updateConn.setRequestProperty("Authorization", "Bearer $accessToken")
                updateConn.setRequestProperty("Content-Type", "text/csv; charset=Shift_JIS")
                updateConn.doOutput = true
                updateConn.outputStream.use { it.write(bytes) }
                updateConn.responseCode
                updateConn.disconnect()
            } else {
                val boundary = "---DriveApiBoundary" + System.currentTimeMillis()
                val createUrl = URL("https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart")
                val createConn = createUrl.openConnection() as HttpURLConnection
                createConn.requestMethod = "POST"
                createConn.setRequestProperty("Authorization", "Bearer $accessToken")
                createConn.setRequestProperty("Content-Type", "multipart/related; boundary=$boundary")
                createConn.doOutput = true
                val metadataJson = JSONObject().apply {
                    put("name", filename)
                    put("mimeType", "text/csv")
                    put("parents", JSONArray().apply { put(folderId) })
                }.toString()
                val bodyStream = createConn.outputStream
                val writer = OutputStreamWriter(bodyStream, "UTF-8")
                writer.write("--$boundary\r\n")
                writer.write("Content-Type: application/json; charset=UTF-8\r\n\r\n")
                writer.write(metadataJson)
                writer.write("\r\n--$boundary\r\n")
                writer.write("Content-Type: text/csv; charset=Shift_JIS\r\n\r\n")
                writer.flush()
                bodyStream.write(bytes)
                bodyStream.flush()
                writer.write("\r\n--$boundary--\r\n")
                writer.flush()
                createConn.responseCode
                createConn.disconnect()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun uploadCsvToCloudWebhook(webhookUrl: String, folderId: String, filename: String, csvString: String) {
        try {
            val jsonPayload = JSONObject().apply {
                put("filename", filename)
                put("folderId", folderId)
                put("csvData", csvString)
            }.toString()

            var currentUrl = webhookUrl
            var redirectCount = 0
            val maxRedirects = 5

            while (redirectCount < maxRedirects) {
                val url = URL(currentUrl)
                val conn = url.openConnection() as HttpURLConnection
                conn.instanceFollowRedirects = false
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                conn.connectTimeout = 15000
                conn.readTimeout = 15000
                conn.doOutput = true

                OutputStreamWriter(conn.outputStream, "UTF-8").use { writer ->
                    writer.write(jsonPayload)
                }

                val responseCode = conn.responseCode
                val responseText = try {
                    conn.inputStream.bufferedReader().use { it.readText() }
                } catch (_: Exception) {
                    ""
                }
                Log.d("WebhookSync", "Response code: $responseCode, Text: $responseText for URL: $currentUrl")

                if ((responseCode == HttpURLConnection.HTTP_MOVED_TEMP) ||
                    (responseCode == HttpURLConnection.HTTP_MOVED_PERM) ||
                    (responseCode == 307) ||
                    (responseCode == 308)
                ) {
                    val location = conn.getHeaderField("Location")
                    conn.disconnect()
                    if (!location.isNullOrEmpty()) {
                        currentUrl = location
                        redirectCount++
                        continue
                    }
                }

                conn.disconnect()
                break
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun handleGoogleDriveOptions() {
        val options = arrayOf("Google Drive に手動保存/共有", "Google Drive フォルダをブラウザで開く", "Google Drive API Bearer Token の設定", "Google Webhook URL の設定", "端末ローカル同期フォルダの設定")
        AlertDialog.Builder(this)
            .setTitle("Google Drive 連携・同期設定")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> saveCurrentLogsToGoogleDriveNative()
                    1 -> openGoogleDriveFolder()
                    2 -> showDriveApiTokenDialog()
                    3 -> showWebhookConfigDialog()
                    4 -> requestSyncFolderLauncher.launch(null)
                }
            }
            .setNegativeButton("閉じる", null)
            .show()
    }

    private fun showDriveApiTokenDialog() {
        val prefs = getSharedPreferences("timecard_prefs", MODE_PRIVATE)
        val currentToken = prefs.getString("google_drive_access_token", "") ?: ""
        val input = EditText(this).apply {
            hint = "ya29.a0A..."
            setText(currentToken)
        }
        AlertDialog.Builder(this)
            .setTitle("Google Drive API Access Token")
            .setMessage("Google Drive v3 API の Bearer Access Token を入力してください:")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                val newToken = input.text.toString().trim()
                prefs.edit { putString("google_drive_access_token", newToken) }
                showToast("Drive API Access Token を保存しました。")
                lifecycleScope.launch { syncLogsToPublicExcelCsv() }
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun showWebhookConfigDialog() {
        val prefs = getSharedPreferences("timecard_prefs", MODE_PRIVATE)
        val currentUrl = prefs.getString("cloud_webhook_url", DEFAULT_WEBHOOK_URL) ?: DEFAULT_WEBHOOK_URL
        val input = EditText(this).apply {
            hint = "https://script.google.com/macros/s/..."
            setText(currentUrl)
        }
        AlertDialog.Builder(this)
            .setTitle("Google Apps Script Webhook URL")
            .setMessage("自動アップロード用 Web App URL を入力してください:")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                val newUrl = input.text.toString().trim()
                prefs.edit { putString("cloud_webhook_url", newUrl) }
                showToast("Webhook URL を保存しました。")
                lifecycleScope.launch { syncLogsToPublicExcelCsv() }
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun openGoogleDriveFolder() {
        val driveUri = "https://drive.google.com/drive/folders/1NlTc2YswYVFXxLSvD08PYjuc1Ev1UOgz".toUri()
        val intent = Intent(Intent.ACTION_VIEW, driveUri)
        try {
            startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
            showToast("Google Driveを開けませんでした。")
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
        val csvFile1 = File(cacheDir, "Robokon_TimecardLog.csv")
        val csvFile2 = File(cacheDir, "attendance_fixed_sync.csv")

        val csvContent = StringBuilder().apply {
            append("ID,ユーザーID,ユーザー名,日付,時刻,アクション\n")
            for (item in attendances) {
                val userName = userMasterMap[item.userId] ?: "未登録ユーザー"
                append("${item.id},${item.userId},$userName,${item.date},${item.time},${item.actionType}\n")
            }
        }.toString()

        OutputStreamWriter(FileOutputStream(csvFile1), "Shift_JIS").use { writer ->
            writer.write(csvContent)
        }
        OutputStreamWriter(FileOutputStream(csvFile2), "Shift_JIS").use { writer ->
            writer.write(csvContent)
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, "Robokon_TimecardLog.csv")
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/csv")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOCUMENTS}/Robokon_Timecard")
                }
                val uri = resolver.insert(MediaStore.Files.getContentUri("external"), contentValues)
                if (uri != null) {
                    resolver.openOutputStream(uri, "rwt")?.use { os ->
                        OutputStreamWriter(os, "Shift_JIS").use { writer ->
                            writer.write(csvContent)
                        }
                    }
                }
            } else {
                val baseDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
                val exportDir = File(baseDir, "Robokon_Timecard")
                if (!exportDir.exists()) {
                    exportDir.mkdirs()
                }
                val publicFile = File(exportDir, "Robokon_TimecardLog.csv")
                csvFile1.copyTo(publicFile, overwrite = true)
                MediaScannerConnection.scanFile(context, arrayOf(publicFile.absolutePath), arrayOf("text/csv"), null)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        val contentUri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            csvFile1,
        )

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, contentUri)
            putExtra(Intent.EXTRA_SUBJECT, "Robokon_TimecardLog.csv")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        // Directly target Google Drive native app's "Save to Drive" activity if installed
        val driveIntent = Intent(shareIntent).apply {
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
