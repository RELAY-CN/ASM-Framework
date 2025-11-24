/*
 * Copyright 2020-2024 Dr (dr@der.kim) and contributors.
 */

package kim.der.asm.mixin

import kim.der.asm.api.annotation.AsmMixin
import kim.der.asm.api.annotation.Mutable
import kim.der.asm.api.annotation.Shadow

/**
 * Shadow ASM: 在 Mixin 类中引用目标类的字段和方法
 *
 * Shadow 允许你在 Mixin 类中声明目标类的成员，然后在其他注入方法中使用它们。
 *
 * 使用场景：
 * 1. 在 Inject 方法中访问目标类的私有字段
 * 2. 在 Inject 方法中调用目标类的私有方法
 * 3. 在 Overwrite 方法中使用目标类的原始方法
 *
 * 与 Accessor 结合使用：
 * - Shadow 在 Mixin 类内部提供字段引用（用于注入方法）
 * - Accessor 为外部代码提供访问接口（生成 getter/setter）
 * - 可以在同一个 Mixin 中同时使用两者：
 *   ```kotlin
 *   @AsmMixin("Test")
 *   class MyMixin {
 *       @Shadow(prefix = "")
 *       private val dynamicString: String? = null  // 在注入方法中使用
 *
 *       @Accessor("dynamicString")
 *       fun getDynamicString(): String { ... }     // 供外部代码使用
 *   }
 *   ```
 *
 * 示例：
 * ```kotlin
 * @AsmMixin("Test")
 * class MyMixin {
 *     @Shadow(prefix = "")
 *     private val dynamicString: String? = null
 *
 *     @AsmInject(method = "testA0()Ljava/lang/String;", target = InjectionPoint.HEAD)
 *     fun injectHead(callback: CallbackInfo) {
 *         // 可以访问 shadow 字段
 *         val value = dynamicString  // 访问目标类的 dynamicString 字段
 *         println("Current value: $value")
 *     }
 * }
 * ```
 *
 * 注意：
 * - prefix = "" 表示直接使用目标成员名，不添加前缀
 * - Shadow 字段必须声明为可空类型（String?）并初始化为 null
 * - Shadow 方法必须有方法体（即使只是抛出异常），但实际不会被调用
 * - 使用 @Mutable 可以移除 final 修饰符，允许修改 final 字段
 * - Shadow 字段和方法必须在 class 中声明，不能在 object 中
 */
@AsmMixin("Test")
class ShadowMixin {
    /**
     * Shadow 字段示例 - 实例字段
     *
     * 在目标类中，这个字段会被映射到：
     * ```java
     * private String dynamicString = "DynamicString";
     * ```
     *
     * 在其他 Mixin 方法中可以使用：
     * ```kotlin
     * val value = dynamicString  // 访问目标类的字段
     * ```
     */
    @Shadow()
    private val dynamicString: String? = null

    /**
     * Shadow 字段示例 - 静态字段
     *
     * 在目标类中，这个字段会被映射到：
     * ```java
     * private static String staticString = "StaticString";
     * ```
     */
    @Shadow()
    private val staticString: String? = null

    /**
     * Shadow 字段示例 - final 字段（使用 @Mutable）
     *
     * @Mutable 会移除 final 修饰符，允许修改该字段
     *
     * 在目标类中，这个字段会被映射到：
     * ```java
     * // 原本是: private static final String staticFinalString = "StaticFinalString";
     * // @Mutable 移除 final 后变成:
     * private static String staticFinalString = "StaticFinalString";
     * ```
     */
    @Shadow()
    @Mutable
    private val staticFinalString: String? = null

    /**
     * Shadow 方法示例 - 实例方法
     *
     * 在目标类中，这个方法会被映射到：
     * ```java
     * public String testA0() {
     *     return dynamicString;
     * }
     * ```
     *
     * 在其他 Mixin 方法中可以调用：
     * ```kotlin
     * val result = testA0()  // 调用目标类的方法
     * ```
     */
    @Shadow()
    private fun testA0(): String = throw UnsupportedOperationException("Shadow method should not be called directly")

    /**
     * Shadow 方法示例 - 静态方法
     *
     * 在目标类中，这个方法会被映射到：
     * ```java
     * public static String testB0() {
     *     return staticFinalString;
     * }
     * ```
     */
    @Shadow()
    private fun testB0(): String = throw UnsupportedOperationException("Shadow method should not be called directly")

    /**
     * Shadow 方法示例 - 带参数的方法
     *
     * 在目标类中，这个方法会被映射到：
     * ```java
     * public String testC0(String string) {
     *     String dynamicString = string + "testC0";
     *     return dynamicString;
     * }
     * ```
     */
    @Shadow()
    private fun testC0(string: String): String = throw UnsupportedOperationException("Shadow method should not be called directly")

    /**
     * Shadow 方法示例 - 静态带参数的方法
     *
     * 在目标类中，这个方法会被映射到：
     * ```java
     * public static String testC1(String string) {
     *     String dynamicString = string + "testC1";
     *     return dynamicString;
     * }
     * ```
     */
    @Shadow()
    private fun testC1(string: String): String = throw UnsupportedOperationException("Shadow method should not be called directly")
}
