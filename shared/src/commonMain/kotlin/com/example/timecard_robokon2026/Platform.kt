package com.example.timecard_robokon2026

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform