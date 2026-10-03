/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * Licensed under the Apache License, Version 2.0.
 */
package dev.cameronpak.muser1.pairing

import org.junit.Assert.*
import org.junit.Test

class BleFramingTest {
    @Test fun boundarySizesRoundTripAtAsymmetricMtus() {
        for (size in listOf(0, 1, 16, 17, 18, 19, 20, 156, 157, 158, 511, 512, 8192)) {
            val original = ByteArray(size) { (it * 31).toByte() }
            val packets = BleFraming.chunks(original, 517)
            assertTrue(packets.all { it.size <= 160 })
            val receiver = BleFraming.Assembler()
            var result: ByteArray? = null
            packets.forEach { receiver.feed(it)?.let { complete -> result = complete } }
            assertArrayEquals("size=$size", original, result)
        }
    }

    @Test fun outOfOrderChunkDropsPartialMessageAndFreshZeroRecovers() {
        val value = ByteArray(512) { it.toByte() }
        val packets = BleFraming.chunks(value, 23)
        val receiver = BleFraming.Assembler()
        assertNull(receiver.feed(packets[0]))
        assertNull(receiver.feed(packets[2]))
        var result: ByteArray? = null
        packets.forEach { receiver.feed(it)?.let { complete -> result = complete } }
        assertArrayEquals(value, result)
    }

    @Test fun changedTotalCannotSpliceFrames() {
        val receiver = BleFraming.Assembler()
        assertNull(receiver.feed(byteArrayOf(0xfe.toByte(), 0, 2, 1)))
        assertNull(receiver.feed(byteArrayOf(0xfe.toByte(), 1, 3, 2)))
        assertArrayEquals(byteArrayOf(9), receiver.feed(byteArrayOf(9)))
    }
}
