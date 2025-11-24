public class Test {
    private String dynamicString = "DynamicString";
    private static String staticString = "StaticString";
    private static final String staticFinalString = "StaticFinalString";


    public String testA0() {
        return dynamicString;
    }

    public static String testB0() {
        return staticFinalString;
    }

    public String testC0(String string) {
        String dynamicString = string + "testC0";
        return dynamicString;
    }

    public static String testC1(String string) {
        String dynamicString = string + "testC1";
        return dynamicString;
    }
}