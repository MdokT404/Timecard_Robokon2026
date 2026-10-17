package com.example.timecard_robokon2026

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.FileReader
import java.io.OutputStreamWriter
import java.time.LocalDate
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
            }
        }

        // ボタンイベント
        btnIn1.setOnClickListener { recordAction("出勤1(加工員)") }
        btnIn2.setOnClickListener { recordAction("出勤2(会計員)") }
        btnIn3.setOnClickListener { recordAction("出勤3(ロボット説明員)") }
        btnOut.setOnClickListener { recordAction("退勤") }
        btnRestIn.setOnClickListener { recordAction("休憩IN") }
        btnRestOut.setOnClickListener { recordAction("休憩OUT") }
        btnExport.setOnClickListener { exportAttendanceLog() }
        btnReloadMaster.setOnClickListener { loadUserMasterFromFile() }
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

    private fun exportAttendanceLog() {
        lifecycleScope.launch {
            val allRecords = dao.getAllLogsOnce()
            if (allRecords.isEmpty()) {
                showToast("No logs.")
                return@launch
            }
            exportDatabaseToCsv(this@MainActivity, allRecords, userMasterMap)
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
                writer.write("${item.id},${item.userId},$userName,${item.date},${item.time},${item.actionType}\n")
            }
        }
        val contentUri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            csvFile,
        )
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, contentUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val shareIntent = Intent.createChooser(sendIntent, "CSVファイルを共有・保存")
        context.startActivity(shareIntent)
    } catch (e: Exception) {
        e.printStackTrace()
    }
}
