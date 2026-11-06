package com.buddydeus.androidmanager.application
import kotlinx.coroutines.channels.Channel

class UpgradeRequests {
    private val pending=Channel<String>(Channel.CONFLATED)
    fun request(token:String) { pending.trySend(token) }
    fun close() { pending.close() }
    suspend fun drain(process:suspend (String)->Unit) { for(token in pending) process(token) }
}
