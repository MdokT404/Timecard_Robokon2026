package com.example.timecard_robokon2026
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "time_card_logs")
data class AttendanceLog(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val userId: String,     //UserID
    val date: String,       //date(例: "2026-10-12")
    val time: String,       //time(例: "09:00:15")
    val actionType: String  //action
)
