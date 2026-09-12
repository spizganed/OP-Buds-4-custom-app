package com.example.oneplusbuds

object OpoProtocol {

    val HELLO = byteArrayOf(
        0xAA.toByte(), 0x07, 0x00, 0x00, 0x00, 0x01, 0x23, 0x00, 0x00, 0x12
    )

    val REGISTER = byteArrayOf(
        0xAA.toByte(), 0x0C, 0x00, 0x00, 0x00, 0x85.toByte(), 0x41,
        0x05, 0x00, 0x00, 0xB5.toByte(), 0x50, 0xA0.toByte(), 0x69
    )

    enum class AncMode(val payload: ByteArray, val label: String) {
        OFF(byteArrayOf(0x01, 0x01, 0x01), "ANC Off"),
        NOISE_CANCELLATION(byteArrayOf(0x01, 0x01, 0x02), "Noise Cancellation"),
        TRANSPARENCY(byteArrayOf(0x01, 0x01, 0x04), "Transparency"),
        ADAPTIVE(byteArrayOf(0x00, 0x08), "Adaptive")
    }

    fun buildAncCommand(mode: AncMode, seq: Int = 0x42): ByteArray {
        return buildPacket(0x0404, mode.payload, seq)
    }

    fun buildGameModeCommand(on: Boolean, seq: Int = 0x42): ByteArray {
        val payload = if (on) byteArrayOf(0x28, 0x01) else byteArrayOf(0x28, 0x00)
        return buildPacket(0x0403, payload, seq)
    }

    private fun buildPacket(cmd: Int, payload: ByteArray, seq: Int): ByteArray {
        val totalLen = 7 + payload.size
        val packet = ByteArray(2 + totalLen)
        packet[0] = 0xAA.toByte()
        packet[1] = totalLen.toByte()
        packet[2] = 0x00
        packet[3] = 0x00
        packet[4] = (cmd and 0xFF).toByte()
        packet[5] = ((cmd shr 8) and 0xFF).toByte()
        packet[6] = seq.toByte()
        packet[7] = (payload.size and 0xFF).toByte()
        packet[8] = 0x00
        payload.copyInto(packet, 9)
        return packet
    }

    fun parseResponse(data: ByteArray): String {
        if (data.isEmpty()) return "empty"
        return "RX: " + data.joinToString(" ") { "%02X".format(it) }
    }
}
