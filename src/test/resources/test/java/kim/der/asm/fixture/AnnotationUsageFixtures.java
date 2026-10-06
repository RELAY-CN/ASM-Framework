/*
 * Copyright 2020-2026 Dr (dr@der.kim) and contributors.
 */
package kim.der.asm.fixture;

/** Java 目标夹具：提供明确的调用副作用、重复槽位读取及可加载的接口常量。 */
public final class AnnotationUsageFixtures {
    /** receiver 独立于被转换类，便于区分改参数、换 receiver 与替换调用。 */
    public static class Receiver {
        public int calls;
        public final String prefix;

        public Receiver(String prefix) { this.prefix = prefix; }

        public String join(String left, String right) {
            calls++;
            return prefix + left + ":" + right;
        }
    }

    /** 外围计数与调用计数分离，整方法跳过和单调用跳过具有不同的可观察结果。 */
    public static class Calls {
        public final Receiver receiver = new Receiver("");
        public int before;
        public int after;

        public String run() {
            before++;
            String result = receiver.join("a", "b");
            after++;
            return result;
        }

        public String twice(String value) { return value.concat(":").concat(value); }

        public String store(String value) {
            value = "next";
            return value;
        }
    }

    /** 不让 Java 编译器内联常量，测试侧通过反射直接观察字段的修饰符。 */
    public interface Constants { int VALUE = Integer.parseInt("7"); }

    /** 无普通方法：类与字段标志修改本身必须足以触发字节码写回。 */
    public abstract static class ConstructorOnly {
        public final int value;
        public ConstructorOnly() { value = 7; }
    }

    /** 两个普通方法用于对照 Copy 冲突、Unique 改名以及 Overwrite 覆盖。 */
    public static class Helpers {
        // 不生成字段初始化指令，删除声明后仍可直接构造目标对象。
        public String URL;
        public String uRL;
        public String value() { return "target"; }
        public String run() { return value(); }
    }

    /** 成员删除不留下调用引用；私有桥接、同步和常量切片共享一个可反编译目标。 */
    public static class Structure {
        public int legacyField;
        public int calls;
        private String secret(String input) { return "secret:" + input; }
        public String legacy() { return "legacy"; }
        public synchronized int locked() { synchronized (this) { return ++calls; } }
        public String version() { return "new"; }
        public String constants() {
            String before = "raw";
            start();
            String inside = "raw";
            end();
            return before.concat(inside).concat("raw");
        }
        public void start() { calls++; }
        public void end() { calls++; }
    }

    /** 增删接口应仅改变类型关系，保留已有业务方法。 */
    public static class RunnableTarget implements Runnable {
        public int calls;
        @Override public void run() { calls++; }
    }
}
