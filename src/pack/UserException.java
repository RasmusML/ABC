package pack;

public class UserException extends IllegalStateException {
  
  public UserException() {
    super();
  }
  
  public UserException(String format, Object... args) {
    super(String.format(format, args));
  }
}
