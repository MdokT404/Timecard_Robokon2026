package com.example.timecard_robokon2026
import androidx.room.*
@Dao
interface AttendanceDao {
    @Insert
    suspend fun insertLog(log: AttendanceLog)
    // CSV出力用に、すべての日時ログを古い順で一括取得する
    @Query("SELECT * FROM time_card_logs ORDER BY date ASC, time ASC")
    suspend fun getAllLogsOnce(): List<AttendanceLog>
}