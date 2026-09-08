/*
 * Copyright 2020-2025 Dr (dr@der.kim) and contributors.
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
import java.util.concurrent.TimeoutException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
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
    fun scannedRegistrationUsesSameStateLockAsPublicQueries() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val registrationStarted = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        val mixin = MultiTargetMixin::class.java
        val bytes = requireNotNull(mixin.classLoader.getResourceAsStream(mixin.name.replace('.', '/') + ".class"))
            .use { it.readBytes() }
        AsmRegistry.registerWithPathMatcher(ExactMixin::class.java) {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            false
        }
        try {
            val query = executor.submit { AsmRegistry.getForTarget("lifecycle/First") }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val registration = executor.submit {
                registrationStarted.countDown()
                AsmRegistry.registerScanned(mixin, bytes)
            }
            assertTrue(registrationStarted.await(5, TimeUnit.SECONDS))
            assertFailsWith<TimeoutException> { registration.get(200, TimeUnit.MILLISECONDS) }
            release.countDown()
            query.get(5, TimeUnit.SECONDS)
            registration.get(5, TimeUnit.SECONDS)
            assertEquals(1, AsmRegistry.getForTarget("lifecycle/First").size)
        } finally {
            release.countDown()
            executor.shutdown()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
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
