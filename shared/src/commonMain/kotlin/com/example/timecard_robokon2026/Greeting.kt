package com.example.timecard_robokon2026

class Greeting {
    private val platform = getPlatform()
    fun greet(): String {
        return sayHello(platform.name)
    }
}