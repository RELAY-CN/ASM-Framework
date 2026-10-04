/*
 * Copyright 2020-2025 Dr (dr@der.kim) and contributors.
 */
package kim.der.asm.api.annotation

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/** 独立验证 handler 值对象的状态变化，避免把 JVM 泛型擦除误解为运行期类型保护。 */
class HandlerValueContractTest {
    @Test
    fun returnValueTypeMismatchFailsAtCallerCast() {
        val callback = CallbackInfo.returnable(42)
        assertEquals(42, callback.getReturnValue<Int>())
        assertThrows(ClassCastException::class.java) {
            val text: String? = callback.getReturnValue<String>()
            text?.length
        }
    }

    @Test
    fun rejectedCancellationPreservesReturnValueAndState() {
        val callback = CallbackInfo.returnable("original")
        assertThrows(IllegalStateException::class.java) { callback.cancel() }
        assertFalse(callback.isCancelled())
        assertFalse(callback.isCancellable())
        assertEquals("original", callback.getReturnValue<String>())
        callback.setReturnValue(null)
        assertNull(callback.getReturnValue<String>())
        assertFalse(callback.isCancelled())
    }

    @Test
    fun cancelledCallbackCanReplaceValueWithoutResuming() {
        val callback = CallbackInfo.cancellable()
        assertTrue(callback.isCancelled())
        callback.setReturnValue("first")
        callback.setReturnValue(null)
        callback.cancel()
        assertTrue(callback.isCancelled())
        assertNull(callback.getReturnValue<Any>())
    }

    @Test
    fun argsExposeLiveArrayAndIteratorRatherThanSnapshots() {
        val values = arrayOf<Any?>("first", "second")
        val args = Args(values)
        val iterator = args.iterator()
        assertSame(values, args.toArray())
        assertEquals("first", iterator.next())
        args[1] = null
        assertNull(iterator.next())
        values[0] = "external"
        assertEquals("external", args.get<String>(0))
    }

    @Test
    fun emptyArgumentGroupAcceptsEmptyReplacementButRejectsValues() {
        val args = Args(emptyArray())
        args.setAll()
        assertEquals(0, args.size)
        assertFalse(args.iterator().hasNext())
        assertThrows(IllegalArgumentException::class.java) { args.setAll("extra") }
        assertThrows(IndexOutOfBoundsException::class.java) { args[0] = "extra" }
        assertEquals(0, args.size())
    }
}
