/*
 * Copyright 2020-2025 Dr (dr@der.kim) and contributors.
 */

package kim.der.asm.api.annotation

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.util.stream.Stream

@DisplayName("Operation 原始操作句柄")
class OperationTest {
    @Nested
    @DisplayName("方法调用场景")
    inner class MethodCallScenarios {
        @Test
        @DisplayName("实例方法应要求显式 receiver，已绑定 receiver 的整方法包裹不再接收 receiver")
        fun instanceMethodRequiresReceiverAndBoundReceiverUsesTargetArgumentsOnly() {
            // Given: 一个真实业务对象，模拟 @WrapOperation 与 @WrapMethod 的两种 receiver 传递方式
            val service = OperationTarget("CNKD")
            val unboundOperation =
                Operation<String>(
                    OperationTarget::class.java,
                    "decorate",
                    STRING_TO_STRING_DESC,
                    false,
                    arrayOf(String::class.java),
                )
            val boundOperation =
                Operation<String>(
                    OperationTarget::class.java,
                    "decorate",
                    STRING_TO_STRING_DESC,
                    arrayOf(String::class.java),
                    service,
                )

            // When
            val unboundResult = unboundOperation.call(service, "player")
            val boundResult = boundOperation.call("player")

            // Then
            assertThat(unboundResult)
                .`as`("未绑定 receiver 的普通实例操作应使用首参作为业务对象")
                .isEqualTo("CNKD:player")
            assertThat(boundResult)
                .`as`("已绑定 receiver 的 @WrapMethod 操作应只接收目标方法参数")
                .isEqualTo("CNKD:player")
            assertThatThrownBy { boundOperation.call(service, "player") }
                .`as`("已绑定 receiver 后再次传 receiver 会让 handler 参数数量错误，失败信息应可诊断")
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessage("Operation kim.der.asm.api.annotation.OperationTest\$OperationTarget.decorate$STRING_TO_STRING_DESC expects 1 argument(s), actual 2")
        }

        @Test
        @DisplayName("构造器操作应通过公共 API 创建对象并保留构造参数状态")
        fun constructorOperationCreatesObjectWithBusinessState() {
            // Given
            val operation =
                Operation<OperationTarget>(
                    OperationTarget::class.java,
                    STRING_CONSTRUCTOR_DESC,
                    arrayOf(String::class.java),
                )

            // When
            val created = operation.call("RTSBox")

            // Then
            assertThat(created.decorate("room"))
                .`as`("构造器 Operation 应等价于原始 NEW + <init> 调用")
                .isEqualTo("RTSBox:room")
        }
    }

    @Nested
    @DisplayName("字段状态场景")
    inner class FieldScenarios {
        @Test
        @DisplayName("实例字段与静态字段读写应更新真实对象状态")
        fun fieldOperationsReadAndWriteInstanceAndStaticState() {
            // Given
            val service = OperationTarget("unused")
            val instanceRead =
                Operation<String>(OperationTarget::class.java, "instanceStatus", STRING_DESC, false)
            val instanceWrite =
                Operation<Unit>(OperationTarget::class.java, "instanceStatus", STRING_DESC, false, write = true)
            val staticRead =
                Operation<String>(OperationTarget::class.java, "staticStatus", STRING_DESC, true)
            val staticWrite =
                Operation<Unit>(OperationTarget::class.java, "staticStatus", STRING_DESC, true, write = true)

            // When
            instanceWrite.call(service, "verified")
            staticWrite.call("published")

            // Then
            assertThat(instanceRead.call(service))
                .`as`("实例字段写入后再次读取应反映同一对象的最新业务状态")
                .isEqualTo("verified")
            assertThat(staticRead.call())
                .`as`("静态字段写入后读取应反映全局共享状态")
                .isEqualTo("published")
        }
    }

    @Nested
    @DisplayName("数组操作场景")
    inner class ArrayScenarios {
        @Test
        @DisplayName("数组读取、写入与长度操作应只改变指定下标并校验索引类型")
        fun arrayOperationsReadWriteLengthAndValidateIndexType() {
            // Given
            val values = arrayOf("alpha", "beta")
            val read = Operation<String>(Array<String>::class.java, write = false)
            val write = Operation<Unit>(Array<String>::class.java, write = true)
            val length = Operation<Int>(Array<String>::class.java)

            // When
            write.call(values, 1, "gamma")

            // Then
            assertThat(read.call(values, 0))
                .`as`("未命中的数组下标不应被写入操作误改")
                .isEqualTo("alpha")
            assertThat(read.call(values, 1))
                .`as`("数组写入 Operation 应只更新指定业务下标")
                .isEqualTo("gamma")
            assertThat(length.call(values))
                .`as`("数组长度 Operation 应保持幂等，不受元素值变化影响")
                .isEqualTo(2)
            assertThatThrownBy { read.call(values, "1") }
                .`as`("handler 误传非 Int 下标时应给出清晰错误")
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessage("Operation [Ljava.lang.String;.<array>:[Ljava.lang.String; requires Int array index")
        }
    }

    @Nested
    @DisplayName("标记操作场景")
    inner class MarkerScenarios {
        @Test
        @DisplayName("局部变量、类型转换与类型判断标记应保留原 JVM 表达式语义")
        fun localCastAndInstanceofMarkersKeepOriginalExpressionSemantics() {
            // Given
            val load = Operation<String>(String::class.java, "<load>")
            val store = Operation<String>(String::class.java, "<store>")
            val cast = Operation<String>(String::class.java, "<checkcast>")
            val instanceOf = Operation<Boolean>(String::class.java, "<instanceof>")

            // When / Then
            assertThat(load.call("local-value"))
                .`as`("LOAD 标记应把本次局部变量读取值原样交给 handler")
                .isEqualTo("local-value")
            assertThat(store.call("next-value"))
                .`as`("STORE 标记应把即将写入的表达式值原样交给 handler")
                .isEqualTo("next-value")
            assertThat(cast.call("cast-value"))
                .`as`("CAST 标记应执行原始 CHECKCAST 语义")
                .isEqualTo("cast-value")
            assertThat(instanceOf.call("candidate"))
                .`as`("INSTANCEOF 标记应返回原始类型判断结果")
                .isTrue()
            assertThat(instanceOf.call(42))
                .`as`("INSTANCEOF 标记对不兼容对象应返回 false 而不是抛异常")
                .isFalse()
        }

        @ParameterizedTest(name = "{0} 错误参数应报告 {2}")
        @MethodSource("kim.der.asm.api.annotation.OperationTest#invalidMarkerArguments")
        @DisplayName("控制流与异常标记应拒绝不兼容参数")
        fun controlMarkersRejectIncompatibleArguments(
            marker: String,
            argument: Any?,
            message: String,
        ) {
            // Given
            val operation = Operation<Any>(String::class.java, marker)

            // When / Then
            assertThatThrownBy { operation.call(argument) }
                .`as`("标记 $marker 的 handler 参数错误应在本地测试中快速暴露")
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessage(message)
        }

        @Test
        @DisplayName("常量操作应幂等返回原始常量并拒绝多余参数")
        fun constantOperationIsIdempotentAndRejectsUnexpectedArguments() {
            // Given
            val operation = Operation<String>("等待核实", String::class.java)

            // When
            val first = operation.call()
            val second = operation()

            // Then
            assertThat(first)
                .`as`("常量 Operation 第一次读取应返回原始常量文本")
                .isEqualTo("等待核实")
            assertThat(second)
                .`as`("operator invoke 应与 call 保持同一幂等契约")
                .isEqualTo(first)
            assertThatThrownBy { operation.call("unused") }
                .`as`("常量读取不消费栈参数，多余参数必须快速失败")
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessage("Operation constant java.lang.String expects 0 argument(s), actual 1")
        }
    }

    companion object {
        private const val STRING_DESC = "Ljava/lang/String;"
        private const val STRING_TO_STRING_DESC = "(Ljava/lang/String;)Ljava/lang/String;"
        private const val STRING_CONSTRUCTOR_DESC = "(Ljava/lang/String;)V"

        @JvmStatic
        fun invalidMarkerArguments(): Stream<Arguments> =
            Stream.of(
                Arguments.of("<jump>", "true", "Operation jump java.lang.String requires Boolean argument"),
                Arguments.of("<switch>", "1", "Operation switch java.lang.String requires Int argument"),
                Arguments.of("<throw>", "boom", "Operation throw java.lang.String requires Throwable argument"),
            )
    }

    private class OperationTarget(
        private val prefix: String,
    ) {
        @JvmField
        var instanceStatus: String = "pending"

        fun decorate(value: String): String = "$prefix:$value"

        companion object {
            @JvmField
            var staticStatus: String = "draft"
        }
    }
}
