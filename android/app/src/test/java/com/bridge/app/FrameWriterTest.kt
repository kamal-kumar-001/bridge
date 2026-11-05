package com.bridge.app

import com.bridge.app.core.network.FrameWriter
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.Collections

class FrameWriterTest {
    @Test fun uiEnqueueDoesNotWriteOnCallerAndPreservesOrder() {
        val caller = Thread.currentThread()
        val gate = CountDownLatch(1)
        val done = CountDownLatch(1)
        val values = Collections.synchronizedList(mutableListOf<Int>())
        val failures = Collections.synchronizedList(mutableListOf<Exception>())
        val writer = FrameWriter { failures.add(it) }
        try {
            writer.write { assertNotSame(caller, Thread.currentThread()); gate.await(); values.add(1) }
            writer.write { values.add(2) }
            writer.write { values.add(3); done.countDown() }
            assertTrue(values.isEmpty())
            gate.countDown()
            assertTrue(done.await(3, TimeUnit.SECONDS))
            assertEquals(listOf(1,2,3), values)
            assertTrue(failures.isEmpty())
        } finally { gate.countDown(); writer.close() }
    }
    @Test fun mainThreadNetworkGuardRejectsOldPathButAcceptsQueuedFrame() {
        val caller = Thread.currentThread()
        val output = object : java.io.ByteArrayOutputStream() {
            override fun write(bytes: ByteArray, offset: Int, size: Int) {
                check(Thread.currentThread() !== caller) { "NetworkOnMainThread" }
                super.write(bytes, offset, size)
            }
        }
        val frame = "{\"type\":\"PAIR_RESPONSE\",\"accepted\":true}".toByteArray()
        try { com.bridge.app.core.protocol.PacketCodec.writeJsonFrame(output, frame); fail("old UI path must fail") }
        catch (_: IllegalStateException) { }
        val writer = FrameWriter { throw AssertionError(it) }
        try {
            writer.write(wait = true) { com.bridge.app.core.protocol.PacketCodec.writeJsonFrame(output, frame) }
            val read = com.bridge.app.core.protocol.PacketCodec.readFrame(java.io.ByteArrayInputStream(output.toByteArray()))
            assertEquals(String(frame), (read as com.bridge.app.core.protocol.PacketCodec.IncomingFrame.JsonFrame).jsonString)
        } finally { writer.close() }
    }
    @Test fun closedWriterRejectsWithoutCallerThreadNetworkFallback() {
        var executed = false
        val writer = FrameWriter { }
        writer.close()
        try { writer.write { executed = true }; fail("closed writer must reject") }
        catch (_: java.util.concurrent.RejectedExecutionException) { }
        assertFalse(executed)
    }
    @Test fun writeFailureReachesDiagnosticsAndFileWorker() {
        val failed = CountDownLatch(1)
        val writer = FrameWriter { failed.countDown() }
        try {
            try { writer.write(wait = true) { throw java.io.IOException("test disconnect") }; fail("must fail") }
            catch (_: java.util.concurrent.ExecutionException) { }
            assertTrue(failed.await(3, TimeUnit.SECONDS))
        } finally { writer.close() }
    }
    @Test fun fileWriteWaitsForFrameCompletion() {
        val writer = FrameWriter { throw AssertionError(it) }
        var complete = false
        try {
            writer.write(wait = true) { complete = true }
            assertTrue(complete)
        } finally { writer.close() }
    }
}
