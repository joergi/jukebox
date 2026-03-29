package com.joergi.jukebox.util

actual object TimeProvider {
    actual fun currentTimeMillis(): Long = System.currentTimeMillis()
}
