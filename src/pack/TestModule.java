package pack;

public interface TestModule extends CompilerModule {

  default void run() {
    CompilerModule.settings.writeOutputToFile = true;
    CompilerModule.settings.catchableErrors = true;
    CompilerModule.settings.writeAstToFile = false;
    CompilerModule.settings.writeCompilerModulesToFile = true;
    CompilerModule.settings.graphvizPath = "C:\\Program Files\\Graphviz\\bin";

    compile("./res/tests/ok/000_temp.abc");

    boolean runCompileTimeTests = true;
    if (runCompileTimeTests) {
      runCompileTimeTests();
    }
  }

  static public class TestCase {
    public String filepath;
    public String errorMessage;
  }

  default TestCase ok(String filepath) {
    TestCase result = new TestCase();
    result.filepath = filepath;
    result.errorMessage = null;
    return result;
  }

  default TestCase fail(String filepath, String errorMessage) {
    TestCase result = new TestCase();
    result.filepath = filepath;
    result.errorMessage = errorMessage;
    return result;
  }

  default void runCompileTimeTests() {

    TestCase[] tests = {
        ok("./res/tests/ok/001_hello.abc"),
        ok("./res/tests/ok/002_types.abc"),
        ok("./res/tests/ok/003_expressions.abc"),
        ok("./res/tests/ok/004_math_operators.abc"),
        ok("./res/tests/ok/005_control.abc"),
        ok("./res/tests/ok/006_example_number_sequences.abc"),
        ok("./res/tests/ok/007_structs.abc"),
        ok("./res/tests/ok/008_function_overloads.abc"),
        ok("./res/tests/ok/009_arrays.abc"),
        ok("./res/tests/ok/010_example_phonebook.abc"),
        ok("./res/tests/ok/011_java_bindings.abc"),
        ok("./res/tests/ok/012_any.abc"),
        ok("./res/tests/ok/013_varargs.abc"),
        ok("./res/tests/ok/014_preload.abc"),
        ok("./res/tests/ok/015_example_europe_graph.abc"),

        fail("./res/tests/bad/constant_definition_out_of_bounds.abc", "Expression 65536 is out of bounds, [-32768; 32767] for type I16."),
        fail("./res/tests/bad/undeclared_function_call.abc", "trying to call an undeclared function: \"nilo_the_magic_dragon\"."),
        fail("./res/tests/bad/function_call_bad_arguments.abc", "function \"is_too_expensive_bad\" parameter types do not match argument types.") };

    CompilerModule.settings.writeCompilerModulesToFile = true;
    CompilerModule.settings.catchableErrors = true;
    CompilerModule.settings.writeOutputToFile = false;
    CompilerModule.settings.writeAstToFile = false;
    CompilerModule.settings.graphvizPath = "C:\\Program Files\\Graphviz\\bin";

    for (TestCase test : tests) {
      runTest(test);
    }

    System.out.printf("all %d tests completed.", tests.length);
  }

  default void reportTestResult(TestCase test, Exception exception) {

    // ok - expect no failure
    if (test.errorMessage == null && exception != null) {
      StringBuilder builder = new StringBuilder();
      builder.append(String.format("test failed - \"%s\"\n", test.filepath));

      builder.append("expected no error.\n");
      builder.append("got error:\n");
      builder.append(exception.getMessage());

      builder.append("\n\n");

      builder.append("Stacktrace:\n");

      System.out.printf("%s", builder);

      exception.printStackTrace(System.out);
      System.exit(0);
    }

    // fail - expect failure, but no failure occurred
    if (test.errorMessage != null && exception == null) {
      StringBuilder builder = new StringBuilder();
      builder.append(String.format("failed test - \"%s\"\n", test.filepath));
      builder.append("expected:\n");
      builder.append(test.errorMessage);
      builder.append("\n");
      builder.append("but test passed.");

      System.out.printf("%s", builder);

      System.exit(0);

    }

    // fail - expected failure message and actual failure message are different.
    if (test.errorMessage != null && exception != null && !exception.getMessage().contains(test.errorMessage)) {
      StringBuilder builder = new StringBuilder();
      builder.append(String.format("failed test - \"%s\"\n", test.filepath));
      builder.append("expected:\n");

      builder.append(test.errorMessage);
      builder.append("\n");

      builder.append("got:\n");
      builder.append(exception.getMessage());

      builder.append("\n\n");
      builder.append("Stacktrace:\n");

      System.out.printf("%s", builder);

      exception.printStackTrace(System.out);
      System.exit(0);
    }

  }

  default void runTest(TestCase test) {
    Exception exception = null;

    try {
      compile(test.filepath);
    } catch (UserException | CompilerException e) {
      exception = e;
    }

    reportTestResult(test, exception);

  }

}
