public class Test {
    private static final String staticFinalString = "StaticFinalString";
    private static String staticString = "StaticString";
    private String dynamicString = "DynamicString";

    public static String testB0() {
        return staticFinalString;
    }

    public static String testC1(String string) {
        String dynamicString = string + "testC1";
        return dynamicString;
    }

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
}