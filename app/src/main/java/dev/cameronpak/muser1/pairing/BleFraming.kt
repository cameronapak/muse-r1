/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * Licensed under the Apache License, Version 2.0.
 */
package dev.cameronpak.muser1.pairing

internal object BleFraming {
    private const val MAGIC = 0xfe
    private const val MAX_MESSAGE = 8192

    fun chunks(data: ByteArray, mtu: Int): List<ByteArray> {
        val packet = minOf((mtu - 3).coerceAtLeast(20), 160)
        val payload = packet - 3
        val count = maxOf(1, (data.size + payload - 1) / payload)
        require(count <= 255)
        return (0 until count).map { index ->
            val from = index * payload
            val to = minOf(data.size, from + payload)
            byteArrayOf(MAGIC.toByte(), index.toByte(), count.toByte()) + data.copyOfRange(from, to)
        }
    }

    class Assembler {
        private var total = 0
        private var next = 0
        private var data = ByteArray(0)

        fun reset() { total = 0; next = 0; data = ByteArray(0) }

        fun feed(packet: ByteArray): ByteArray? {
            if (packet.size < 3 || packet[0].toInt() and 0xff != MAGIC) return packet.copyOf()
            val index = packet[1].toInt() and 0xff
            val incomingTotal = packet[2].toInt() and 0xff
            if (incomingTotal == 0) { reset(); return null }
            if (index == 0 || incomingTotal != total) { reset(); total = incomingTotal }
            if (index != next || index >= total || data.size + packet.size - 3 > MAX_MESSAGE) {
                reset(); return null
            }
            data += packet.copyOfRange(3, packet.size)
            next++
            if (next != total) return null
            return data.also { reset() }
        }
    }
}
