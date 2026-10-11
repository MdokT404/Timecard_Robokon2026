package com.example.timecard_robokon2026
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
import java.io.FileReader
import java.io.FileWriter
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
            tvDigitalClock.text = LocalTime.now().format(timeFormatter)
            clockHandler.postDelayed(this, 1000)
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
        loadUserMasterFromFile()// 起動時に外部ファイルから名簿を読み込む
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
            if (userId.length != 5 || !userId.all { it.isDigit() }) {
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
        btnIn1.setOnClickListener {recordAction("出勤1(加工員)")}
        btnIn2.setOnClickListener {recordAction("出勤2(会計員)")}
        btnIn3.setOnClickListener {recordAction("出勤3(ロボット説明員)")}
        btnOut.setOnClickListener {recordAction("退勤")}
        btnRestIn.setOnClickListener {recordAction("休憩IN")}
        btnRestOut.setOnClickListener {recordAction("休憩OUT")}
        btnExport.setOnClickListener {exportAttendanceLog() }
        btnReloadMaster.setOnClickListener { loadUserMasterFromFile() }
    }

    // 外部テキストファイルからID名簿をロード
    private fun loadUserMasterFromFile() {
        try {
            val masterFile = File(getExternalFilesDir(null), "user_master.txt")
            if (!masterFile.exists()) {
                masterFile.createNewFile()
                masterFile.writeText("24500,Sample000\n25000,Sample001\n")
                showToast("user_master.txt を新規作成しました")
            }
            val newMap = mutableMapOf<String, String>()
            BufferedReader(FileReader(masterFile)).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    if (line!!.trim().isEmpty()) continue
                    val tokens = line!!.split(",")
                    if (tokens.size >= 2) {
                        newMap[tokens[0].trim()] = tokens[1].trim()
                    }
                }
            }
            userMasterMap = newMap
            showToast("Loaded master.txt（${userMasterMap.size}名）")
        } catch (e: Exception) {
            showToast("Failed to load Master.")
        }
    }

    private fun getUserName(userId: String): String = userMasterMap[userId] ?: "未登録ユーザー。F R U?"
    private fun exportAttendanceLog() {
        lifecycleScope.launch {
            val allRecords = dao.getAllLogsOnce()
            if (allRecords.isEmpty()) {
                showToast("No logs.")
                return@launch
            }

            val csvBody = StringBuilder().apply {
                append("ID, ユーザー名, 日付, 時刻, アクション\n")
                for (record in allRecords) {
                    append("${record.userId},")
                    append("${getUserName(record.userId)},")
                    append("${record.date},")
                    append("${record.time},")
                    append("${record.actionType}\n")
                }
            }
            try {
                val file = File(getExternalFilesDir(null), "timecard_log_${System.currentTimeMillis()}.csv")
                FileWriter(file).use { it.write(csvBody.toString()) }
                showToast("Success to export: ${file.name}")
                shareCsvFile(file)
            } catch (e: Exception) {
                showToast("Failed to export")
            }
        }
    }

    private fun shareCsvFile(file: File) {
        val fileUri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, fileUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "ログファイルの共有・保存"))
    }

    override fun onResume() { super.onResume(); clockHandler.post(clockRunnable) }
    override fun onPause() { super.onPause(); clockHandler.removeCallbacks(clockRunnable) }
    private fun showToast(msg: String) { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
}
