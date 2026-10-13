package com.seniorcall.signaling

interface ISignalingClient {
    val isConnected: Boolean
    fun connect()
    fun disconnect()
    fun send(msg: Any)
    fun addListener(listener: SignalingClient.Listener)
    fun removeListener(listener: SignalingClient.Listener)
}
