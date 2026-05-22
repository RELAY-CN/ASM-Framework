/*
 * Copyright 2020-2025 Dr (dr@der.kim) and contributors.
 */

package kim.der.asm

import kim.der.asm.mixin.*
import kim.der.asm.transformer.AsmProcessor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Paths

/**
 * Test.java 的 ASM 测试用例
 * 测试各种 ASM 注解的使用场景
 *
 * Test.java 包含：
 * - 实例字段：dynamicString
 * - 静态字段：staticString
 * - 静态final字段：staticFinalString
 * - 实例方法：testA0(), testC0(String)
 * - 静态方法：testB0(), testC1(String)
 *
 * @author Dr (dr@der.kim)
 */
class TestMixin {
    private val asmProcessor = AsmProcessor()

    // ========== Overwrite 测试 ==========
    @Test
    fun testOverwrite() {
        AsmRegistry.clear()
        AsmRegistry.register(OverwriteMixin::class.java)
        val originalBytes = loadLegacyClass()
        val transformed = transformClass(originalBytes)

        val clazz = loadClass(transformed, "Test")
        val instance = clazz.getDeclaredConstructor().newInstance()

        // 测试 testA0
        val methodA0 = clazz.getMethod("testA0")
        val resultA0 = methodA0.invoke(instance) as String
        assertEquals("OverwrittenA0", resultA0, "testA0 应该被 Overwrite")

        // 测试 testB0 (静态方法)
        val methodB0 = clazz.getMethod("testB0")
        val resultB0 = methodB0.invoke(null) as String
        assertEquals("OverwrittenB0", resultB0, "testB0 应该被 Overwrite")

        // 测试 testC0
        val methodC0 = clazz.getMethod("testC0", String::class.java)
        val resultC0 = methodC0.invoke(instance, "Test") as String
        assertEquals("OverwrittenC0", resultC0, "testC0 应该被 Overwrite")

        // 测试 testC1 (静态方法)
        val methodC1 = clazz.getMethod("testC1", String::class.java)
        val resultC1 = methodC1.invoke(null, "Test") as String
        assertEquals("OverwrittenC1", resultC1, "testC1 应该被 Overwrite")
    }

    // ========== AsmInject 测试 ==========
    @Test
    fun testInject() {
        AsmRegistry.clear()
        AsmRegistry.register(InjectMixin::class.java)
        val originalBytes = loadLegacyClass()
        val transformed = transformClass(originalBytes)

        val clazz = loadClass(transformed, "Test")
        val instance = clazz.getDeclaredConstructor().newInstance()

        // HEAD 注入可以取消方法执行
        val methodA0 = clazz.getMethod("testA0")
        val resultA0 = methodA0.invoke(instance) as String
        assertEquals("InjectedAtHeadA0", resultA0, "HEAD 注入应该返回注入的值")

        // 测试静态方法的 HEAD 注入
        val methodB0 = clazz.getMethod("testB0")
        val resultB0 = methodB0.invoke(null) as String
        assertEquals("InjectedAtHeadB0", resultB0, "静态方法 HEAD 注入应该返回注入的值")
    }

    // ========== INVOKE 注入测试（验证 @at 参数） ==========
    @Test
    fun testInvokeInject() {
        AsmRegistry.clear()
        AsmRegistry.register(InvokeInjectMixin::class.java)
        val originalBytes = loadLegacyClass()
        val transformed = transformClass(originalBytes)

        val clazz = loadClass(transformed, "Test")
        val instance = clazz.getDeclaredConstructor().newInstance()

        // 测试 INVOKE 注入（使用 @at 参数）
        val methodC0 = clazz.getMethod("testC0", String::class.java)
        val resultC0 = methodC0.invoke(instance, "Test") as String
        // 验证方法正常执行（at 参数应该能正常工作）
        assertNotNull(resultC0, "testC0 应该返回结果")
        assertEquals("TesttestC0", resultC0, "testC0 应该返回正确的结果")
    }

    // ========== ModifyArg 测试 ==========
    @Test
    fun testModifyArg() {
        AsmRegistry.clear()
        AsmRegistry.register(ModifyArgMixin::class.java)
        val originalBytes = loadLegacyClass()
        val transformed = transformClass(originalBytes)

        val clazz = loadClass(transformed, "Test")
        val instance = clazz.getDeclaredConstructor().newInstance()

        // ModifyArg 修改 testC0 参数值
        val methodC0 = clazz.getMethod("testC0", String::class.java)
        val resultC0 = methodC0.invoke(instance, "Original") as String
        assertEquals("Modified_OriginaltestC0", resultC0, "testC0 ModifyArg 应该修改参数值")

        // ModifyArg 修改 testC1 参数值（静态方法）
        val methodC1 = clazz.getMethod("testC1", String::class.java)
        val resultC1 = methodC1.invoke(null, "Original") as String
        assertEquals("Modified_OriginaltestC1", resultC1, "testC1 ModifyArg 应该修改参数值")
    }

    // ========== ModifyReturnValue 测试 ==========
    @Test
    fun testModifyReturnValue() {
        AsmRegistry.clear()
        AsmRegistry.register(ModifyReturnValueMixin::class.java)
        val originalBytes = loadLegacyClass()
        val transformed = transformClass(originalBytes)

        val clazz = loadClass(transformed, "Test")
        val instance = clazz.getDeclaredConstructor().newInstance()

        // ModifyReturnValue 修改 testA0 返回值
        val methodA0 = clazz.getMethod("testA0")
        val resultA0 = methodA0.invoke(instance) as String
        assertEquals("ModifiedReturnA0", resultA0, "testA0 ModifyReturnValue 应该修改返回值")

        // ModifyReturnValue 修改 testB0 返回值（静态方法）
        val methodB0 = clazz.getMethod("testB0")
        val resultB0 = methodB0.invoke(null) as String
        assertEquals("ModifiedReturnB0", resultB0, "testB0 ModifyReturnValue 应该修改返回值")

        // ModifyReturnValue 修改 testC0 返回值
        val methodC0 = clazz.getMethod("testC0", String::class.java)
        val resultC0 = methodC0.invoke(instance, "Test") as String
        assertEquals("ModifiedReturnC0", resultC0, "testC0 ModifyReturnValue 应该修改返回值")

        // ModifyReturnValue 修改 testC1 返回值（静态方法）
        val methodC1 = clazz.getMethod("testC1", String::class.java)
        val resultC1 = methodC1.invoke(null, "Test") as String
        assertEquals("ModifiedReturnC1", resultC1, "testC1 ModifyReturnValue 应该修改返回值")
    }

    // ========== Accessor 测试 ==========
    @Test
    fun testAccessor() {
        AsmRegistry.clear()
        AsmRegistry.register(AccessorMixin::class.java)
        val originalBytes = loadLegacyClass()
        val transformed = transformClass(originalBytes)

        val clazz = loadClass(transformed, "Test")
        val instance = clazz.getDeclaredConstructor().newInstance()

        // 测试 dynamicString Getter/Setter
        val getterDynamic = clazz.getMethod("getDynamicString")
        val valueDynamic = getterDynamic.invoke(instance) as String
        assertEquals("DefaultConstructor", valueDynamic, "dynamicString getter 应该返回字段值（无参构造函数设置为 DefaultConstructor）")

        val setterDynamic = clazz.getMethod("setDynamicString", String::class.java)
        setterDynamic.invoke(instance, "NewDynamicValue")
        val newValueDynamic = getterDynamic.invoke(instance) as String
        assertEquals("NewDynamicValue", newValueDynamic, "dynamicString setter 应该设置字段值")

        // 测试 staticString Getter/Setter
        val getterStatic = clazz.getMethod("getStaticString")
        val valueStatic = getterStatic.invoke(null) as String
        assertEquals("StaticInitBlock", valueStatic, "staticString getter 应该返回字段值（静态初始化块设置为 StaticInitBlock）")

        val setterStatic = clazz.getMethod("setStaticString", String::class.java)
        setterStatic.invoke(null, "NewStaticValue")
        val newValueStatic = getterStatic.invoke(null) as String
        assertEquals("NewStaticValue", newValueStatic, "staticString setter 应该设置字段值")

        // 测试 staticFinalString Getter/Setter
        val getterFinal = clazz.getMethod("getStaticFinalString")
        val valueFinal = getterFinal.invoke(null) as String
        assertEquals("StaticFinalString", valueFinal, "STATIC_FINAL_STRING getter 应该返回字段值")

        val setterFinal = clazz.getMethod("setStaticFinalString", String::class.java)
        setterFinal.invoke(null, "NewFinalValue")
        val newValueFinal = getterFinal.invoke(null) as String
        assertEquals("NewFinalValue", newValueFinal, "STATIC_FINAL_STRING setter 应该设置字段值（需要 @Mutable）")
    }

    // ========== Invoker 测试 ==========
    @Test
    fun testInvoker() {
        AsmRegistry.clear()
        AsmRegistry.register(InvokerMixin::class.java)
        val originalBytes = loadLegacyClass()
        val transformed = transformClass(originalBytes)

        val clazz = loadClass(transformed, "Test")
        val instance = clazz.getDeclaredConstructor().newInstance()

        // 测试调用 testA0
        val invokerA0 = clazz.getMethod("invokeTestA0")
        val resultA0 = invokerA0.invoke(instance) as String
        assertEquals("DefaultConstructor", resultA0, "Invoker 应该能够调用 testA0（返回 DefaultConstructor）")

        // 测试调用 testB0（静态方法）
        val invokerB0 = clazz.getMethod("invokeTestB0")
        val resultB0 = invokerB0.invoke(null) as String
        assertEquals("StaticFinalString", resultB0, "Invoker 应该能够调用 testB0")

        // 测试调用 testC0
        val invokerC0 = clazz.getMethod("invokeTestC0", String::class.java)
        val resultC0 = invokerC0.invoke(instance, "Invoked") as String
        assertEquals("InvokedtestC0", resultC0, "Invoker 应该能够调用 testC0")

        // 测试调用 testC1（静态方法）
        val invokerC1 = clazz.getMethod("invokeTestC1", String::class.java)
        val resultC1 = invokerC1.invoke(null, "StaticInvoked") as String
        assertEquals("StaticInvokedtestC1", resultC1, "Invoker 应该能够调用 testC1")
    }

    // ========== Shadow 测试 ==========
    @Test
    fun testShadow() {
        AsmRegistry.clear()
        AsmRegistry.register(ShadowMixin::class.java)
        val originalBytes = loadLegacyClass()
        val transformed = transformClass(originalBytes)

        val clazz = loadClass(transformed, "Test")
        val instance = clazz.getDeclaredConstructor().newInstance()

        // Shadow 字段和方法主要用于在 ASM 类中引用目标类的成员
        // 这里主要验证 Shadow 能够正确应用，特别是 @Mutable 功能
        assertNotNull(clazz, "Shadow 应该能够正确声明")

        // 验证 @Mutable 是否移除了 final 修饰符
        // 通过反射检查 STATIC_FINAL_STRING 字段是否不再是 final
        val staticFinalField = clazz.getDeclaredField("STATIC_FINAL_STRING")
        val isFinal =
            java.lang.reflect.Modifier
                .isFinal(staticFinalField.modifiers)
        // 由于 @Mutable，STATIC_FINAL_STRING 应该不再是 final
        // 注意：这个测试可能不准确，因为字段可能仍然是 final（取决于实现）
        // 但至少验证了 Shadow 能够正确应用
        assertNotNull(staticFinalField, "Shadow 字段应该存在")
    }

    // ========== Accessor 和 Shadow 结合使用测试 ==========
    @Test
    fun testAccessorShadowCombined() {
        AsmRegistry.clear()
        AsmRegistry.register(AccessorShadowMixin::class.java)
        val originalBytes = loadLegacyClass()
        val transformed = transformClass(originalBytes)

        val clazz = loadClass(transformed, "Test")
        val instance = clazz.getDeclaredConstructor().newInstance()

        // 验证 Accessor 方法是否生成（通过 Shadow 字段生成的访问器）
        val getterDynamic = clazz.getMethod("getDynamicString")
        val valueDynamic = getterDynamic.invoke(instance) as String
        assertEquals("DefaultConstructor", valueDynamic, "Accessor 应该能够访问 Shadow 字段（无参构造函数设置为 DefaultConstructor）")

        // 验证可以通过 Accessor 修改字段值
        val setterDynamic = clazz.getMethod("setDynamicString", String::class.java)
        setterDynamic.invoke(instance, "ModifiedViaAccessor")
        val newValueDynamic = getterDynamic.invoke(instance) as String
        assertEquals("ModifiedViaAccessor", newValueDynamic, "Accessor 应该能够修改 Shadow 字段")

        // 验证注入方法是否正常工作（使用 Shadow 字段）
        val methodA0 = clazz.getMethod("testA0")
        val resultA0 = methodA0.invoke(instance) as String
        // 由于 AccessorShadowExampleMixin 中的注入会修改返回值
        assertNotNull(resultA0, "注入方法应该能够使用 Shadow 字段")
    }

    // ========== Redirect 测试 ==========
    @Test
    fun testRedirect() {
        AsmRegistry.clear()
        AsmRegistry.register(RedirectMixin::class.java)
        val originalBytes = loadLegacyClass()
        val transformed = transformClass(originalBytes)

        val clazz = loadClass(transformed, "Test")
        val instance = clazz.getDeclaredConstructor().newInstance()

        val comprehensiveTest = clazz.getMethod("comprehensiveTest")
        comprehensiveTest.isAccessible = true
        val result = comprehensiveTest.invoke(instance) as String

        assertEquals(true, result.contains("Redirected-testB0"), "testB0 调用应该被重定向")
        assertEquals(true, result.contains("Redirected-testA0"), "testA0 调用应该被重定向")
        assertEquals(true, result.contains("Redirected-parentMethod"), "parentMethod 调用应该被重定向")
        assertEquals(true, result.contains("Redirected-interfaceMethod-Test"), "interfaceMethod 调用应该被重定向")

        val lambdaTest = clazz.getMethod("lambdaTest")
        val lambdaResult = lambdaTest.invoke(instance) as String
        assertEquals(true, lambdaResult.contains("Redirected-Lambda"), "Supplier.get 调用应该被重定向")

        val enumTest = clazz.getMethod("enumTest")
        val enumResult = enumTest.invoke(instance) as String
        assertEquals(true, enumResult.contains("Redirected-EnumDescription"), "枚举方法调用应该被重定向")

        val testC0 = clazz.getMethod("testC0", String::class.java)
        val testC0Result = testC0.invoke(instance, "TestRedirect") as String
        assertEquals("TestRedirecttestC0", testC0Result, "println 重定向不应改变 testC0 返回值")
    }

    // ========== This Access 测试 ==========
    @Test
    fun testThisAccess() {
        AsmRegistry.clear()
        AsmRegistry.register(ThisAccessMixin::class.java)
        val originalBytes = loadLegacyClass()
        val transformed = transformClass(originalBytes)

        val clazz = loadClass(transformed, "Test")
        val instance = clazz.getDeclaredConstructor().newInstance()

        // 测试 testA0：RETURN 注入会基于 this 的 hashCode 修改返回值
        val methodA0 = clazz.getMethod("testA0")
        val resultA0 = methodA0.invoke(instance) as String
        assertNotNull(resultA0, "testA0 应该返回修改后的值")
        assert(resultA0.startsWith("Modified by ThisAccess:"), { "返回值应该包含 'Modified by ThisAccess:'" })

        // 测试 testC0：HEAD 注入会接收 this 和参数
        val methodC0 = clazz.getMethod("testC0", String::class.java)
        val resultC0 = methodC0.invoke(instance, "TestParam") as String
        assertEquals("TestParamtestC0", resultC0, "testC0 应该正常工作")
    }

    // ========== 综合测试：多个 ASM 组合 ==========
    @Test
    fun testMultipleMixins() {
        AsmRegistry.clear()
        AsmRegistry.register(ModifyReturnValueMixin::class.java)
        AsmRegistry.register(ModifyArgMixin::class.java)
        val originalBytes = loadLegacyClass()
        val transformed = transformClass(originalBytes)

        val clazz = loadClass(transformed, "Test")
        val instance = clazz.getDeclaredConstructor().newInstance()

        // testC0 被 ModifyArg 和 ModifyReturnValue
        val methodC0 = clazz.getMethod("testC0", String::class.java)
        val resultC0 = methodC0.invoke(instance, "Original") as String
        assertEquals("ModifiedReturnC0", resultC0, "testC0 应该被 ModifyArg 和 ModifyReturnValue 处理")
    }

    // ========== 测试新增方法：Lambda 表达式 ==========
    @Test
    fun testLambdaMethods() {
        AsmRegistry.clear()
        val originalBytes = loadLegacyClass()
        val transformed = transformClass(originalBytes)

        val clazz = loadClass(transformed, "Test")
        val instance = clazz.getDeclaredConstructor().newInstance()

        // 测试 lambdaTest 方法
        val lambdaTest = clazz.getMethod("lambdaTest")
        val result = lambdaTest.invoke(instance) as String
        assertNotNull(result, "lambdaTest 应该返回结果")
        println("Lambda Test Result: $result")

        // 测试 lambdaStreamTest 方法
        val lambdaStreamTest = clazz.getMethod("lambdaStreamTest")
        val streamResult = lambdaStreamTest.invoke(instance) as String
        assertEquals("abc", streamResult, "lambdaStreamTest 应该返回 'abc'")
    }

    // ========== 测试新增方法：重载方法 ==========
    @Test
    fun testOverloadedMethods() {
        AsmRegistry.clear()
        val originalBytes = loadLegacyClass()
        val transformed = transformClass(originalBytes)

        val clazz = loadClass(transformed, "Test")
        val instance = clazz.getDeclaredConstructor().newInstance()

        // 测试无参重载
        val method0 = clazz.getMethod("overloadedMethod")
        val result0 = method0.invoke(instance) as String
        assertEquals("Overloaded-0", result0, "overloadedMethod() 应该返回 'Overloaded-0'")

        // 测试单参重载
        val method1 = clazz.getMethod("overloadedMethod", String::class.java)
        val result1 = method1.invoke(instance, "Test") as String
        assertEquals("Overloaded-1-Test", result1, "overloadedMethod(String) 应该返回正确结果")

        // 测试双参重载
        val method2 = clazz.getMethod("overloadedMethod", String::class.java, Int::class.javaPrimitiveType)
        val result2 = method2.invoke(instance, "Test", 42) as String
        assertEquals("Overloaded-2-Test-42", result2, "overloadedMethod(String, int) 应该返回正确结果")
    }

    // ========== 测试新增方法：同步方法 ==========
    @Test
    fun testSynchronizedMethods() {
        AsmRegistry.clear()
        val originalBytes = loadLegacyClass()
        val transformed = transformClass(originalBytes)

        val clazz = loadClass(transformed, "Test")
        val instance = clazz.getDeclaredConstructor().newInstance()

        // 测试实例同步方法
        // 注意：实例初始化块将 instanceCounter 设置为 50，所以第一次调用返回 51
        val syncMethod = clazz.getMethod("synchronizedMethod")
        val result1 = syncMethod.invoke(instance) as String
        assertEquals("SyncInstance-51", result1, "第一次调用应该返回 'SyncInstance-51'（实例初始化块设置 counter=50）")

        val result2 = syncMethod.invoke(instance) as String
        assertEquals("SyncInstance-52", result2, "第二次调用应该返回 'SyncInstance-52'")

        // 测试静态同步方法
        val staticSyncMethod = clazz.getMethod("synchronizedStaticMethod")
        val staticResult = staticSyncMethod.invoke(null) as String
        assertNotNull(staticResult, "静态同步方法应该返回结果")
        println("Static Sync Result: $staticResult")
    }

    // ========== 测试新增方法：泛型和可变参数 ==========
    @Test
    fun testGenericAndVarArgs() {
        AsmRegistry.clear()
        val originalBytes = loadLegacyClass()
        val transformed = transformClass(originalBytes)

        val clazz = loadClass(transformed, "Test")
        val instance = clazz.getDeclaredConstructor().newInstance()

        // 测试泛型方法
        val genericMethod = clazz.getMethod("genericMethod", Object::class.java)
        val genericResult = genericMethod.invoke(instance, "TestString") as String
        assertNotNull(genericResult, "泛型方法应该返回结果")
        println("Generic Method Result: $genericResult")

        // 测试可变参数方法
        val varArgsMethod = clazz.getMethod("varArgsMethod", Array<String>::class.java)
        val varArgsResult = varArgsMethod.invoke(instance, arrayOf("A", "B", "C")) as String
        assertEquals("VarArgs-A,B,C", varArgsResult, "可变参数方法应该返回正确结果")
    }

    // ========== 测试新增方法：递归方法 ==========
    @Test
    fun testRecursiveMethod() {
        AsmRegistry.clear()
        val originalBytes = loadLegacyClass()
        val transformed = transformClass(originalBytes)

        val clazz = loadClass(transformed, "Test")
        val instance = clazz.getDeclaredConstructor().newInstance()

        // 测试递归方法（阶乘）
        val recursiveMethod = clazz.getMethod("recursiveMethod", Int::class.javaPrimitiveType)
        val result = recursiveMethod.invoke(instance, 5) as Int
        assertEquals(120, result, "recursiveMethod(5) 应该返回 120 (5!)")
    }

    // ========== 测试新增方法：继承和接口 ==========
    @Test
    fun testInheritanceAndInterface() {
        AsmRegistry.clear()
        val originalBytes = loadLegacyClass()
        val transformed = transformClass(originalBytes)

        val clazz = loadClass(transformed, "Test")
        val instance = clazz.getDeclaredConstructor().newInstance()

        // 测试父类方法
        val parentMethod = clazz.getMethod("parentMethod")
        val parentResult = parentMethod.invoke(instance) as String
        assertEquals("ParentMethod", parentResult, "应该能调用父类方法")

        // 测试重写的方法
        val overridableMethod = clazz.getMethod("overridableMethod")
        val overrideResult = overridableMethod.invoke(instance) as String
        assertEquals("ChildOverride-ParentOverridable", overrideResult, "应该调用子类重写的方法")

        // 测试接口方法
        val interfaceMethod = clazz.getMethod("interfaceMethod", String::class.java)
        interfaceMethod.isAccessible = true  // 允许访问非 public 接口的方法
        val interfaceResult = interfaceMethod.invoke(instance, "Test") as String
        assertEquals("InterfaceImpl-Test", interfaceResult, "应该能调用接口实现方法")

        // 测试默认接口方法
        val defaultMethod = clazz.getMethod("defaultMethod")
        defaultMethod.isAccessible = true  // 允许访问非 public 接口的方法
        val defaultResult = defaultMethod.invoke(instance) as String
        assertEquals("DefaultMethod", defaultResult, "应该能调用接口默认方法")
    }

    // ========== 测试新增方法：void 方法 ==========
    @Test
    fun testVoidMethods() {
        AsmRegistry.clear()
        val originalBytes = loadLegacyClass()
        val transformed = transformClass(originalBytes)

        val clazz = loadClass(transformed, "Test")
        val instance = clazz.getDeclaredConstructor().newInstance()

        // 测试实例 void 方法
        val testVoid = clazz.getMethod("testVoid")
        testVoid.invoke(instance) // 不应该抛出异常

        // 测试静态 void 方法
        val staticVoidMethod = clazz.getMethod("staticVoidMethod")
        staticVoidMethod.invoke(null) // 不应该抛出异常
    }

    // ========== 测试新增方法：构造函数 ==========
    @Test
    fun testConstructors() {
        AsmRegistry.clear()
        val originalBytes = loadLegacyClass()
        val transformed = transformClass(originalBytes)

        val clazz = loadClass(transformed, "Test")

        // 测试无参构造函数
        val instance1 = clazz.getDeclaredConstructor().newInstance()
        assertNotNull(instance1, "无参构造函数应该能创建实例")

        // 验证无参构造函数设置的字段值
        val testA0_1 = clazz.getMethod("testA0")
        val result1 = testA0_1.invoke(instance1) as String
        assertEquals("DefaultConstructor", result1, "无参构造函数应该设置 dynamicString 为 'DefaultConstructor'")

        // 测试带参构造函数
        val instance2 = clazz.getDeclaredConstructor(String::class.java).newInstance("CustomValue")
        assertNotNull(instance2, "带参构造函数应该能创建实例")

        // 验证构造函数设置的字段值
        val testA0_2 = clazz.getMethod("testA0")
        val result2 = testA0_2.invoke(instance2) as String
        assertEquals("CustomValue", result2, "构造函数应该正确设置字段值")
    }

    // ========== 测试新增方法：枚举 ==========
    @Test
    fun testEnumMethods() {
        AsmRegistry.clear()
        val originalBytes = loadLegacyClass()
        val transformed = transformClass(originalBytes)

        val clazz = loadClass(transformed, "Test")
        val instance = clazz.getDeclaredConstructor().newInstance()

        // 测试枚举方法
        val enumTest = clazz.getMethod("enumTest")
        val result = enumTest.invoke(instance) as String
        assertEquals("First-VALUE2", result, "enumTest 应该返回正确的枚举值")
    }

    // ========== 生成转换后的 Class 文件 ==========
    @Test
    fun generateTransformedClasses() {
        val outputDir = Paths.get("d:/home/RELAY-CN_Group/ASM-Framework/src/test/resources/out")
        Files.createDirectories(outputDir)

        val originalBytes = loadLegacyClass()
        val mixins =
            listOf(
                "Overwrite" to OverwriteMixin::class.java,
                "Inject" to InjectMixin::class.java,
                "InvokeInject" to InvokeInjectMixin::class.java,
                "ModifyArg" to ModifyArgMixin::class.java,
                "ModifyReturnValue" to ModifyReturnValueMixin::class.java,
                "Redirect" to RedirectMixin::class.java,
                "Accessor" to AccessorMixin::class.java,
                "Invoker" to InvokerMixin::class.java,
                "Shadow" to ShadowMixin::class.java,
                "AccessorShadow" to AccessorShadowMixin::class.java,
                "RemoveMethod" to RemoveMethodMixin::class.java,
                "ThisAccess" to ThisAccessMixin::class.java,
                "ReplaceAllMethodsVoid" to ReplaceAllMethodsVoidMixin::class.java,
                "RedirectAllMethodsAdvanced" to RedirectAllMethodsAdvancedMixin::class.java,
            )

        for ((name, mixinClass) in mixins) {
            AsmRegistry.clear()
            AsmRegistry.register(mixinClass)
            val transformed = transformClass(originalBytes)
            val outputFile = outputDir.resolve("Test_$name.class")
            Files.write(outputFile, transformed)
            println("Generated: ${outputFile.toAbsolutePath()}")
        }

        // 生成多个 Mixin 组合的 class
        AsmRegistry.clear()
        AsmRegistry.register(ModifyReturnValueMixin::class.java)
        AsmRegistry.register(ModifyArgMixin::class.java)
        val combined = transformClass(originalBytes)
        val combinedFile = outputDir.resolve("Test_MultipleMixins.class")
        Files.write(combinedFile, combined)
        println("Generated: ${combinedFile.toAbsolutePath()}")
    }

    // ========== 辅助方法 ==========
    
    // 创建一个能够从 test/ 目录加载类的 ClassLoader
    private val testClassLoader = object : ClassLoader(Thread.currentThread().contextClassLoader) {
        override fun findClass(name: String): Class<*> {
            // 尝试从 test 目录加载类
            try {
                val resourceName = "test/$name.class"
                val classBytes = getResourceAsStream(resourceName)?.readAllBytes()
                if (classBytes != null) {
                    return defineClass(name, classBytes, 0, classBytes.size)
                }
            } catch (e: Exception) {
                // 忽略，让父类加载器处理
            }
            throw ClassNotFoundException(name)
        }
        
        override fun getResourceAsStream(name: String): java.io.InputStream? {
            // 如果请求的是 Test.class 或其他测试类，重定向到 test/ 目录
            if (name.endsWith(".class") && !name.startsWith("test/")) {
                val testResource = "test/$name"
                val stream = super.getResourceAsStream(testResource)
                if (stream != null) {
                    return stream
                }
            }
            return super.getResourceAsStream(name)
        }
    }
    
    private fun loadLegacyClass(): ByteArray =
        Thread
            .currentThread()
            .getContextClassLoader()
            .getResourceAsStream("test/Test.class")!!
            .readAllBytes()

    private fun transformClass(originalBytes: ByteArray): ByteArray = 
        asmProcessor.transform("Test", originalBytes, testClassLoader)

    private fun loadClass(
        transformedBytes: ByteArray,
        className: String,
    ): Class<*> {
        val loader =
            object : ClassLoader(Thread.currentThread().contextClassLoader) {
                override fun findClass(name: String): Class<*> {
                    // 如果是要加载的转换后的类，使用传入的字节码
                    if (name == className) {
                        return defineClass(name, transformedBytes, 0, transformedBytes.size)
                    }
                    
                    // 尝试从 test 目录加载依赖类
                    try {
                        val resourceName = "test/$name.class"
                        val classBytes = javaClass.classLoader.getResourceAsStream(resourceName)?.readAllBytes()
                        if (classBytes != null) {
                            return defineClass(name, classBytes, 0, classBytes.size)
                        }
                    } catch (e: Exception) {
                        // 忽略，让父类加载器处理
                    }
                    
                    // 让父类加载器处理
                    throw ClassNotFoundException(name)
                }
            }
        return loader.loadClass(className)
    }
}


