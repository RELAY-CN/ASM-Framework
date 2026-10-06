/*
 * Copyright 2020-2026 Dr (dr@der.kim) and contributors.
 */

package kim.der.asm

import kim.der.asm.api.annotation.AsmInject
import kim.der.asm.api.annotation.AsmMixin
import kim.der.asm.data.AsmInfo
import kim.der.asm.func.Find
import kim.der.asm.transformer.AsmProcessor
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AsmRegistryLifecycleTest {
    @BeforeEach
    fun setUp() {
        AsmRegistry.clear()
        handlerCalls = 0
    }

    @AfterEach
    fun tearDown() {
        AsmRegistry.clear()
    }

    @Test
    fun unmatchedDynamicNamesAreNotRetained() {
        repeat(10_000) { index ->
            assertTrue(AsmRegistry.getForTarget("generated/Unmatched$index").isEmpty())
        }

        assertEquals(0, targetCache().size)
    }

    @Test
    fun matchingDynamicNamesHaveBoundedCache() {
        AsmRegistry.registerWithPathMatcher(ExactMixin::class.java) { true }

        repeat(10_000) { index ->
            val name = "generated/Matched$index"
            assertEquals(name, AsmRegistry.getForTarget(name).single().targetClassName)
        }

        assertEquals(4096, targetCache().size)
        assertFalse(targetCache().containsKey("generated/Matched0"))
        assertTrue(targetCache().containsKey("generated/Matched9999"))
    }

    @Test
    fun registeringSameClassTwiceKeepsOneEntry() {
        repeat(2) { AsmRegistry.register(ExactMixin::class.java) }

        assertEquals(1, AsmRegistry.getForTarget("lifecycle/Target").size)
        assertEquals(1, transformAndLoad("lifecycle/Target").getMethod("value").invoke(null))
        assertEquals(1, handlerCalls)
    }

    @Test
    fun repeatedTargetsRunHandlerOnce() {
        AsmRegistry.register(RepeatedTargetMixin::class.java)

        assertEquals(1, AsmRegistry.getForTarget("lifecycle/Target").size)
        assertEquals(1, transformAndLoad("lifecycle/Target").getMethod("value").invoke(null))
        assertEquals(1, handlerCalls)
    }

    @Test
    fun scannedRegistrationCompletesWhilePathMatcherIsRunning() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val firstCall = AtomicBoolean(true)
        val executor = Executors.newFixedThreadPool(2)
        val mixin = MultiTargetMixin::class.java
        val bytes = requireNotNull(mixin.classLoader.getResourceAsStream(mixin.name.replace('.', '/') + ".class"))
            .use { it.readBytes() }
        AsmRegistry.registerWithPathMatcher(ExactMixin::class.java) {
            if (firstCall.getAndSet(false)) {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
            true
        }
        try {
            val query = executor.submit<List<AsmInfo>> { AsmRegistry.getForTarget("lifecycle/First") }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val registration = executor.submit {
                AsmRegistry.registerScanned(mixin, bytes)
            }
            registration.get(5, TimeUnit.SECONDS)
            assertFalse(query.isDone)
            val expected = listOf(ExactMixin::class.java, mixin)
            assertEquals(expected, AsmRegistry.getForTarget("lifecycle/First").map { it.asmClass })
            release.countDown()
            assertEquals(listOf(ExactMixin::class.java), query.get(5, TimeUnit.SECONDS).map { it.asmClass })
            assertEquals(expected, AsmRegistry.getForTarget("lifecycle/First").map { it.asmClass })
        } finally {
            release.countDown()
            executor.shutdown()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun matcherCanAddPathRegistrationWithoutChangingCurrentQuerySnapshot() {
        val firstCall = AtomicBoolean(true)
        AsmRegistry.registerWithPathMatcher(ExactMixin::class.java) {
            if (firstCall.getAndSet(false)) {
                AsmRegistry.registerWithPathMatcher(RepeatedTargetMixin::class.java) { true }
            }
            true
        }

        assertEquals(listOf(ExactMixin::class.java), AsmRegistry.getForTarget("lifecycle/First").map { it.asmClass })
        assertFalse(targetCache().containsKey("lifecycle/First"))
        assertEquals(
            listOf(ExactMixin::class.java, RepeatedTargetMixin::class.java),
            AsmRegistry.getForTarget("lifecycle/First").map { it.asmClass },
        )
    }

    @Test
    fun matcherCanClearAndReplaceRegistrationsWithoutRestoringOldCache() {
        AsmRegistry.registerWithPathMatcher(ExactMixin::class.java) {
            AsmRegistry.clear()
            AsmRegistry.register(MultiTargetMixin::class.java)
            true
        }

        // 清理后注册数量相同，也不能把旧生命周期的查询结果写回新缓存。
        assertEquals(listOf(ExactMixin::class.java), AsmRegistry.getForTarget("lifecycle/First").map { it.asmClass })
        assertFalse(targetCache().containsKey("lifecycle/First"))
        assertEquals(listOf(MultiTargetMixin::class.java), AsmRegistry.getForTarget("lifecycle/First").map { it.asmClass })
    }

    @Test
    fun clearingDuringMatchingDoesNotResurrectRegistrations() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        AsmRegistry.registerWithPathMatcher(ExactMixin::class.java) {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            true
        }
        try {
            val query = executor.submit<List<AsmInfo>> { AsmRegistry.getForTarget("lifecycle/Target") }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            executor.submit { AsmRegistry.clear() }.get(5, TimeUnit.SECONDS)
            assertFalse(query.isDone)
            release.countDown()
            assertEquals(listOf(ExactMixin::class.java), query.get(5, TimeUnit.SECONDS).map { it.asmClass })
            assertTrue(targetCache().isEmpty())
            assertTrue(AsmRegistry.getForTarget("lifecycle/Target").isEmpty())
        } finally {
            release.countDown()
            executor.shutdown()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun matcherExceptionPropagatesWithoutCachingPartialResults() {
        val failure = IllegalStateException("matcher failed")
        var fail = true
        AsmRegistry.registerWithPathMatcher(ExactMixin::class.java) { true }
        AsmRegistry.registerWithPathMatcher(RepeatedTargetMixin::class.java) {
            if (fail) throw failure
            true
        }

        assertSame(failure, assertFailsWith<IllegalStateException> { AsmRegistry.getForTarget("lifecycle/Target") })
        assertTrue(targetCache().isEmpty())
        fail = false
        assertEquals(2, AsmRegistry.getForTarget("lifecycle/Target").size)
    }

    @Test
    fun multiTargetQueryCarriesCurrentOwner() {
        AsmRegistry.register(MultiTargetMixin::class.java)

        assertEquals("lifecycle/First", AsmRegistry.getForTarget("lifecycle/First").single().targetClassName)
        assertEquals("lifecycle/Second", AsmRegistry.getForTarget("lifecycle/Second").single().targetClassName)
    }

    @Test
    fun ordinaryMixinExecutesOnEachTarget() {
        AsmRegistry.register(MultiTargetMixin::class.java)

        val first = transformAndLoad("lifecycle/First")
        val second = transformAndLoad("lifecycle/Second")
        assertEquals(1, first.getMethod("value").invoke(null))
        assertEquals(1, second.getMethod("value").invoke(null))
        assertEquals(2, handlerCalls)
    }

    @Test
    fun ordinaryMixinExecutesOnPathMatchedTarget() {
        AsmRegistry.registerWithPathMatcher(MultiTargetMixin::class.java) { it == "lifecycle/Matched" }

        assertEquals(1, transformAndLoad("lifecycle/Matched").getMethod("value").invoke(null))
        assertEquals(1, handlerCalls)
    }

    @Test
    fun publicFiveParameterConstructorRemainsAvailable() {
        val constructor = AsmInfo::class.java.getConstructor(
            Class::class.java,
            List::class.java,
            Find::class.java,
            Int::class.javaPrimitiveType,
            Long::class.javaPrimitiveType,
        )
        val info = constructor.newInstance(ExactMixin::class.java, listOf("lifecycle/Target"), null, 1000, 0L)
        assertEquals(ExactMixin::class.java, info.asmClass)
        assertEquals(2000, info.copy(priority = 2000).priority)
    }

    private fun transformAndLoad(name: String): Class<*> {
        val writer = ClassWriter(0)
        writer.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null)
        writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "value", "()I", null, null).apply {
            visitCode()
            visitInsn(Opcodes.ICONST_1)
            visitInsn(Opcodes.IRETURN)
            visitMaxs(1, 0)
            visitEnd()
        }
        writer.visitEnd()
        val bytes = AsmProcessor().transform(name, writer.toByteArray(), javaClass.classLoader)
        return object : ClassLoader(javaClass.classLoader) {
            fun loadTarget(): Class<*> = defineClass(name.replace('/', '.'), bytes, 0, bytes.size)
        }.loadTarget()
    }

    private fun targetCache(): Map<*, *> {
        val field = AsmRegistry::class.java.getDeclaredField("targetCache")
        field.isAccessible = true
        return field.get(null) as Map<*, *>
    }

    @AsmMixin("lifecycle/Target")
    class ExactMixin {
        @AsmInject(method = "value()I")
        fun beforeValue() {
            handlerCalls++
        }
    }

    @AsmMixin(targets = ["lifecycle/First", "lifecycle/Second"])
    class MultiTargetMixin {
        @AsmInject(method = "value()I")
        fun beforeValue() {
            handlerCalls++
        }
    }

    @AsmMixin(targets = ["lifecycle/Target", "lifecycle/Target"])
    class RepeatedTargetMixin {
        @AsmInject(method = "value()I")
        fun beforeValue() {
            handlerCalls++
        }
    }

    companion object {
        var handlerCalls = 0
    }
}
