package pack;

public class CompilerException extends IllegalStateException {

  public CompilerException(String format, Object... args) {
    super(String.format(format, args));
  }
}
