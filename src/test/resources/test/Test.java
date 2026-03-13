import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

// 接口定义
interface TestInterface {
    String interfaceMethod(String input);
    
    default String defaultMethod() {
        return "DefaultMethod";
    }
}

// 函数式接口
@FunctionalInterface
interface TestFunctionalInterface {
    int calculate(int a, int b);
}

// 父类
class TestParent {
    protected String parentField = "ParentField";
    
    public String parentMethod() {
        return "ParentMethod";
    }
    
    public String overridableMethod() {
        return "ParentOverridable";
    }
}

// 主测试类
public class Test extends TestParent implements TestInterface {
    // 静态字段
    private static final String STATIC_FINAL_STRING = "StaticFinalString";
    private static String staticString = "StaticString";
    private static int staticCounter = 0;
    
    // 实例字段
    private String dynamicString = "DynamicString";
    private int instanceCounter = 0;
    
    // 内部类
    public static class StaticInnerClass {
        public String innerMethod() {
            return "StaticInnerClass";
        }
    }
    
    public class InnerClass {
        public String innerMethod() {
            return "InnerClass-" + dynamicString;
        }
    }
    
    // 构造函数
    public Test() {
        this.dynamicString = "DefaultConstructor";
    }
    
    public Test(String value) {
        this.dynamicString = value;
    }
    
    // 静态方法
    public static String testB0() {
        return STATIC_FINAL_STRING;
    }
    
    public static String testC1(String string) {
        String dynamicString = string + "testC1";
        return dynamicString;
    }
    
    public static synchronized String synchronizedStaticMethod() {
        staticCounter++;
        return "SyncStatic-" + staticCounter;
    }
    
    public static void staticVoidMethod() {
        System.out.println("StaticVoidMethod");
    }
    
    // 实例方法
    public void testVoid() {
        System.out.println("testVoid");
    }
    
    public String testA0() {
        return dynamicString;
    }
    
    public String testC0(String string) {
        String dynamicString = string + "testC0";
        System.out.println("testC0 called with: " + dynamicString);
        return dynamicString;
    }
    
    public synchronized String synchronizedMethod() {
        instanceCounter++;
        return "SyncInstance-" + instanceCounter;
    }
    
    // 重载方法
    public String overloadedMethod() {
        return "Overloaded-0";
    }
    
    public String overloadedMethod(String arg) {
        return "Overloaded-1-" + arg;
    }
    
    public String overloadedMethod(String arg1, int arg2) {
        return "Overloaded-2-" + arg1 + "-" + arg2;
    }
    
    // 覆盖父类方法
    @Override
    public String overridableMethod() {
        return "ChildOverride-" + super.overridableMethod();
    }
    
    // 实现接口方法
    @Override
    public String interfaceMethod(String input) {
        return "InterfaceImpl-" + input;
    }
    
    // 反射测试方法
    public String reflectionTest() throws Exception {
        // 通过反射调用私有方法
        Method privateMethod = Test.class.getDeclaredMethod("privateMethod");
        privateMethod.setAccessible(true);
        return (String) privateMethod.invoke(this);
    }
    
    private String privateMethod() {
        return "PrivateMethod-" + dynamicString;
    }
    
    // 反射获取字段
    public String reflectionFieldTest() throws Exception {
        java.lang.reflect.Field field = Test.class.getDeclaredField("dynamicString");
        field.setAccessible(true);
        return "ReflectionField-" + field.get(this);
    }
    
    // Lambda 表达式测试
    public String lambdaTest() {
        // 简单 Lambda
        Supplier<String> supplier = () -> "LambdaSupplier";
        
        // Lambda 带参数
        Function<String, String> function = s -> "LambdaFunction-" + s;
        
        // 方法引用
        Function<String, Integer> methodRef = String::length;
        
        // 函数式接口
        TestFunctionalInterface calculator = (a, b) -> a + b;
        
        return supplier.get() + "-" + function.apply("Test") + "-" + 
               methodRef.apply("Hello") + "-" + calculator.calculate(10, 20);
    }
    
    // Lambda 与集合操作
    public String lambdaStreamTest() {
        List<String> list = Arrays.asList("A", "B", "C");
        return list.stream()
                   .map(s -> s.toLowerCase())
                   .reduce("", (a, b) -> a + b);
    }
    
    // 闭包测试
    public Function<Integer, Integer> closureTest(int base) {
        return x -> x + base;
    }
    
    // 异常处理
    public String exceptionTest(boolean throwException) throws CustomException {
        try {
            if (throwException) {
                throw new CustomException("TestException");
            }
            return "NoException";
        } catch (CustomException e) {
            return "CaughtException-" + e.getMessage();
        } finally {
            System.out.println("Finally block");
        }
    }
    
    // 泛型方法
    public <T> String genericMethod(T value) {
        return "Generic-" + value.getClass().getSimpleName() + "-" + value.toString();
    }
    
    // 可变参数
    public String varArgsMethod(String... args) {
        return "VarArgs-" + String.join(",", args);
    }
    
    // 递归方法
    public int recursiveMethod(int n) {
        if (n <= 1) return 1;
        return n * recursiveMethod(n - 1);
    }
    
    // 静态初始化块
    static {
        staticString = "StaticInitBlock";
        staticCounter = 100;
    }
    
    // 实例初始化块
    {
        instanceCounter = 50;
    }
    
    // 枚举测试
    public enum TestEnum {
        VALUE1("First"),
        VALUE2("Second");
        
        private final String description;
        
        TestEnum(String description) {
            this.description = description;
        }
        
        public String getDescription() {
            return description;
        }
    }
    
    public String enumTest() {
        return TestEnum.VALUE1.getDescription() + "-" + TestEnum.VALUE2.name();
    }
    
    // 自定义异常
    public static class CustomException extends Exception {
        public CustomException(String message) {
            super(message);
        }
    }
    
    // 综合测试方法
    public String comprehensiveTest() throws Exception {
        StringBuilder result = new StringBuilder();
        
        // 静态方法调用
        result.append(testB0()).append("|");
        
        // 实例方法调用
        result.append(testA0()).append("|");
        
        // 继承方法调用
        result.append(parentMethod()).append("|");
        result.append(overridableMethod()).append("|");
        
        // 接口方法调用
        result.append(interfaceMethod("Test")).append("|");
        result.append(defaultMethod()).append("|");
        
        // Lambda 测试
        result.append(lambdaTest()).append("|");
        
        // 反射测试
        result.append(reflectionTest()).append("|");
        
        // 内部类测试
        StaticInnerClass staticInner = new StaticInnerClass();
        result.append(staticInner.innerMethod()).append("|");
        
        InnerClass inner = new InnerClass();
        result.append(inner.innerMethod()).append("|");
        
        // 泛型测试
        result.append(genericMethod(123)).append("|");
        
        // 枚举测试
        result.append(enumTest());
        
        return result.toString();
    }
    
    // Main 方法用于独立测试
    public static void main(String[] args) {
        try {
            Test test = new Test("MainTest");
            System.out.println("Comprehensive Test: " + test.comprehensiveTest());
            System.out.println("Lambda Stream Test: " + test.lambdaStreamTest());
            System.out.println("Recursive Test: " + test.recursiveMethod(5));
            
            Function<Integer, Integer> closure = test.closureTest(100);
            System.out.println("Closure Test: " + closure.apply(50));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
