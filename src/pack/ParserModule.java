package pack;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public interface ParserModule {

  static public class AstProgram {
    public Path workspacePath;
    public List<AstCompilationUnit> compilationUnits;
  }

  static public class AstCompilationUnit {
    public SourceFile sourceFile;
    public boolean hasProgramEntry;
    public List<AstFunction> functions;
    public List<AstStruct> structs;

    //public List<String> import/abc/DependencyNames;
    public Set<String> javaLibraryDependencyNames;
  }

  static public interface AstExpression extends AstAssignment {
  }

  static public interface AstStatement {
  }

  static public interface AstAssignment {
  }

  static public class AstNew implements AstAssignment {
    public Location location;
    public List<AstExpression> arraySizes;
  }

  static public enum AstTypeCategory {
    I8, I16, I32, I64, F32, F64, Bool, Char, Struct, Void, String, Object, Any;
  }

  static public class AstType {
    public AstTypeCategory category;
    public String structName;

    public boolean isVarargs;
    public int arrayDimension;  // 0 if not an array.
  }

  static public class AstTypeCast implements AstExpression {
    public AstType type;
    public AstExpression expression;
    public boolean implicit;
  }

  static public class AstDeclaration implements AstStatement {
    public Location location;
    public AstType type;
    public String identifier;
    public AstAssignment optionalInit;  // gets set by the typechecker if no assignment has been set in the source code.
  }

  static public class AstWhileLoop implements AstStatement {
    public AstExpression condition;
    public List<AstStatement> body;
  }

  static public class AstDefinition implements AstStatement {
    public AstVariable lhs;
    public AstAssignment rhs;
  }

  static public class AstIfStatement implements AstStatement {
    public AstExpression condition;
    public List<AstStatement> ifBody;
    public List<AstStatement> elseBody;
  }

  static public class AstReturn implements AstStatement {
    public Location location;
    public AstExpression returnExpression;
  }

  static public class AstFunctionCall implements AstExpression, AstStatement {
    public Location location;
    public String name;
    public List<AstExpression> arguments;
  }

  static public class AstVariable implements AstExpression {
    public Location location;
    public String name;
    public List<AstExpression> arrayExpressions;  // e.g. a[1+2][3][fn()]
    public AstType type;  // inferred by the typechecker;
    public AstVariable child;   // e.g. "a.b" => b would be children
    public boolean readOnly;
  }

  static public enum TokenLiteralType {
    I32, I64, F32, F64, Char, Struct, Bool, String, Object;
  }

  static public class AstLiteral implements AstExpression {
    public Location location;
    public AstType type;
    public String value;

    public long integerValue;
    public double floatingPointValue;
  }

  static public class AstBinaryOperator implements AstExpression {
    public AstExpression lhs;
    public String operator;
    public AstExpression rhs;
  }

  static public class AstParenthesis implements AstExpression {
    public AstExpression body;
  }

  static public class AstUnaryOperator implements AstExpression {
    public String operator;
    public AstExpression body;
  }

  static public class AstParameterDeclaration {
    public Location location;
    public AstType type;
    public String name;
  }

  static public class AstFunction {
    // header
    public Location location;
    public String name;
    public List<AstParameterDeclaration> parameters;
    public AstType returnType;

    // body
    public List<AstStatement> bodyStatements;

    // no body
    public boolean hasJavaLibraryBinding;
    public String javaLibraryName;
  }

  static public class Parser {
    public int at;
    public List<Token> tokens;

    public SourceFile sourceCode;
  }

  static public class AstStructField {
    public Location location;
    public AstType type;
    public String name;
  }

  static public class AstStruct {
    public Location location; // header
    public String name;

    // body
    public List<AstStructField> fields;

    // no body
    public boolean hasJavaLibraryBinding;
    public String javaLibraryName;
  }

  default AstProgram parseUnits(String mainFilepath) {
    Path mainPath = Paths.get(mainFilepath);
    String content = readFileToString(mainPath);

    SourceFile sourceFile = new SourceFile();
    sourceFile.content = content;
    sourceFile.filename = mainFilepath;

    AstProgram result = new AstProgram();
    result.compilationUnits = new ArrayList<>();
    result.workspacePath = mainPath.getParent();

    List<Token> tokens = lex(sourceFile);
    AstCompilationUnit astUnit = parse(tokens, sourceFile);
    result.compilationUnits.add(astUnit);

    return result;
  }

  private AstCompilationUnit parse(List<Token> tokens, SourceFile sourceCode) {
    AstCompilationUnit result = new AstCompilationUnit();
    result.functions = new ArrayList<>();
    result.structs = new ArrayList<>();
    result.javaLibraryDependencyNames = new HashSet<>();
    result.sourceFile = sourceCode;

    addRuntimeSupportModule(result);
    addPreloadModule(result);

    Parser parser = new Parser();
    parser.at = 0;
    parser.tokens = tokens;
    parser.sourceCode = sourceCode;

    while (!isEndOfTokens(parser)) {
      if (isFunction(parser)) {
        AstFunction function = parseFunction(parser);
        result.functions.add(function);
      } else if (isStruct(parser)) {
        AstStruct struct = parseStruct(parser);
        result.structs.add(struct);
      } else if (isComment(parser)) {
        eatToken(parser);
      } else {
        Token token = peekToken(parser);
        reportError(parser, token, "unexpected token \"%s\" in file scope.", token.value);
      }
    }

    return result;
  }

  default String readFileToString(Path path) {
    String result = null;
    try {
      result = Files.readString(path);
    } catch (IOException e) {
    }
    return result;
  }

  private void addRuntimeSupportModule(AstCompilationUnit result) {
    result.javaLibraryDependencyNames.add("RuntimeSupport");
  }

  private AstFunction createJavaLibraryHeaderFunction(String name, String javaLibraryName, List<AstParameterDeclaration> parameters, AstType returnType) {
    AstFunction function = new AstFunction();
    function.name = name;
    function.hasJavaLibraryBinding = true;
    function.javaLibraryName = javaLibraryName;
    function.location = new Location();
    function.parameters = parameters;
    function.bodyStatements = new ArrayList<>();
    function.returnType = returnType;
    return function;
  }

  // @TODO: make an ABC Preload module and import it automatically
  private void addPreloadModule(AstCompilationUnit astUnit) {
    astUnit.javaLibraryDependencyNames.add("Preload");

    { // print :: (format: string, args: .. any) #lib "Preload";
      List<AstParameterDeclaration> parameters = new ArrayList<>();

      AstParameterDeclaration parameter1 = new AstParameterDeclaration();
      parameter1.name = "format";
      parameter1.type = astType_primitive(AstTypeCategory.String, false, 0);
      parameters.add(parameter1);

      AstParameterDeclaration parameter2 = new AstParameterDeclaration();
      parameter2.name = "args";
      parameter2.type = astType_primitive(AstTypeCategory.Any, true, 0);
      parameters.add(parameter2);

      AstType returnType = astType_primitive(AstTypeCategory.Void, false, 0);
      AstFunction printFunction = createJavaLibraryHeaderFunction("print", "Preload", parameters, returnType);

      astUnit.functions.add(printFunction);
    }

    { // ensure :: (condition: bool, errorFormat: string, errorArgs .. any) #lib "Preload";
      List<AstParameterDeclaration> parameters = new ArrayList<>();

      AstParameterDeclaration parameter1 = new AstParameterDeclaration();
      parameter1.name = "condition";
      parameter1.type = astType_primitive(AstTypeCategory.Bool, false, 0);
      parameters.add(parameter1);

      AstParameterDeclaration parameter2 = new AstParameterDeclaration();
      parameter2.name = "errorFormat";
      parameter2.type = astType_primitive(AstTypeCategory.String, false, 0);
      parameters.add(parameter2);

      AstParameterDeclaration parameter3 = new AstParameterDeclaration();
      parameter3.name = "errorArgs";
      parameter3.type = astType_primitive(AstTypeCategory.Any, true, 0);
      parameters.add(parameter3);

      AstType returnType = astType_primitive(AstTypeCategory.Void, false, 0);
      AstFunction printFunction = createJavaLibraryHeaderFunction("ensure", "Preload", parameters, returnType);

      astUnit.functions.add(printFunction);
    }

    { // exit :: (code: i32) #lib "Preload";
      List<AstParameterDeclaration> parameters = new ArrayList<>();

      AstParameterDeclaration parameter = new AstParameterDeclaration();
      parameter.name = "code";
      parameter.type = astType_primitive(AstTypeCategory.I32, false, 0);
      parameters.add(parameter);

      AstType returnType = astType_primitive(AstTypeCategory.Void, false, 0);
      AstFunction printFunction = createJavaLibraryHeaderFunction("exit", "Preload", parameters, returnType);

      astUnit.functions.add(printFunction);
    }

    { // length :: (s: string) -> i32 #lib "Preload";
      List<AstParameterDeclaration> parameters = new ArrayList<>();

      AstParameterDeclaration parameter = new AstParameterDeclaration();
      parameter.name = "s";
      parameter.type = astType_primitive(AstTypeCategory.String, false, 0);
      parameters.add(parameter);

      AstType returnType = astType_primitive(AstTypeCategory.I32, false, 0);
      AstFunction printFunction = createJavaLibraryHeaderFunction("length", "Preload", parameters, returnType);

      astUnit.functions.add(printFunction);
    }

    { // char_at :: (s: string, index: i32) -> char #lib "Preload";
      List<AstParameterDeclaration> parameters = new ArrayList<>();

      AstParameterDeclaration parameter1 = new AstParameterDeclaration();
      parameter1.name = "s";
      parameter1.type = astType_primitive(AstTypeCategory.String, false, 0);
      parameters.add(parameter1);

      AstParameterDeclaration parameter2 = new AstParameterDeclaration();
      parameter2.name = "index";
      parameter2.type = astType_primitive(AstTypeCategory.I32, false, 0);
      parameters.add(parameter2);

      AstType returnType = astType_primitive(AstTypeCategory.Char, false, 0);
      AstFunction printFunction = createJavaLibraryHeaderFunction("char_at", "Preload", parameters, returnType);

      astUnit.functions.add(printFunction);
    }

  }

  private AstStruct parseStruct(Parser parser) {
    AstStruct result = new AstStruct();
    result.fields = new ArrayList<>();
    result.location = new Location();

    Token name = expectToken(parser, TokenType.Identifier);
    result.name = name.value;

    result.location.lineStart = name.location.lineStart;
    result.location.charStart = name.location.charStart;

    expectToken(parser, "::");
    Token structKeywordToken = expectToken(parser, "struct");

    result.location.lineEnd = structKeywordToken.location.lineEnd;
    result.location.charEnd = structKeywordToken.location.charEnd;

    Token maybeFromJavaLibrary = peekToken(parser);
    if (matches(maybeFromJavaLibrary, "#lib")) {
      eatToken(parser);

      Token literalToken = peekToken(parser);
      if (literalToken.literalType != TokenLiteralType.String) reportError(parser, literalToken, "expected string literal.");
      eatToken(parser);

      Token endToken = expectToken(parser, ";");

      result.hasJavaLibraryBinding = true;
      result.javaLibraryName = literalToken.value;

      result.location.lineEnd = endToken.location.lineEnd;
      result.location.charEnd = endToken.location.charEnd;

      return result;
    }

    expectToken(parser, "{");
    while (true) {

      if (isEndOfTokens(parser)) {
        reportEndOfFileError(parser, "struct body is not complete, but end of file has been reached.");
      }

      Token bodyEndToken = peekToken(parser);
      if (matches(bodyEndToken, "}")) break;

      AstStructField field = parseField(parser);
      result.fields.add(field);
    }
    expectToken(parser, "}");

    return result;
  }

  private boolean isStruct(Parser parser) {
    Token token1 = peekToken(parser);
    if (token1 == null) return false;

    Token token2 = peekAhead(parser, 1);
    if (token2 == null) return false;

    Token token3 = peekAhead(parser, 2);
    if (token3 == null) return false;

    if (!matches(token1, TokenType.Identifier)) return false;
    if (!matches(token2, "::")) return false;
    if (!matches(token3, "struct")) return false;

    return true;
  }

  private Token peekAhead(Parser parser, int by) {
    if (parser.at + by >= parser.tokens.size()) return null;
    return parser.tokens.get(parser.at + by);
  }

  private boolean isFunction(Parser parser) {
    Token token1 = peekToken(parser);
    if (!matches(token1, TokenType.Identifier)) return false;

    Token token2 = peekAhead(parser, 1);
    if (!matches(token2, "::")) return false;

    Token token3 = peekAhead(parser, 2);
    if (!matches(token3, "(")) return false;

    return true;
  }

  default AstLiteral astLiteral(String value, AstType type, Location location) {
    AstLiteral result = new AstLiteral();
    result.value = value;
    result.type = type;
    result.location = location;
    return result;
  }

  private AstType astType_from_token(TokenLiteralType literalType, boolean isVarargs, int arrayDimensions) {
    if (literalType == TokenLiteralType.I32) return astType_primitive(AstTypeCategory.I32, isVarargs, arrayDimensions);
    if (literalType == TokenLiteralType.I64) return astType_primitive(AstTypeCategory.I64, isVarargs, arrayDimensions);
    if (literalType == TokenLiteralType.F32) return astType_primitive(AstTypeCategory.F32, isVarargs, arrayDimensions);
    if (literalType == TokenLiteralType.F64) return astType_primitive(AstTypeCategory.F64, isVarargs, arrayDimensions);
    if (literalType == TokenLiteralType.Char) return astType_primitive(AstTypeCategory.Char, isVarargs, arrayDimensions);
    if (literalType == TokenLiteralType.Bool) return astType_primitive(AstTypeCategory.Bool, isVarargs, arrayDimensions);
    if (literalType == TokenLiteralType.String) return astType_primitive(AstTypeCategory.String, isVarargs, arrayDimensions);
    if (literalType == TokenLiteralType.Struct) return astType_primitive(AstTypeCategory.Struct, isVarargs, arrayDimensions);
    if (literalType == TokenLiteralType.Object) return astType_primitive(AstTypeCategory.Object, isVarargs, arrayDimensions);
    throw new CompilerException("unexpected literal type: %s", literalType);
  }

  default AstType astType_struct(String structName, boolean isVarargs, int arrayDimension) {
    return astType(AstTypeCategory.Struct, structName, isVarargs, arrayDimension);
  }

  default AstType astType_primitive(AstTypeCategory type, boolean isVarargs, int arrayDimension) {
    return astType(type, null, isVarargs, arrayDimension);
  }

  default AstType astType(AstTypeCategory type, String structName, boolean isVarargs, int arrayDimension) {
    AstType result = new AstType();
    result.category = type;
    result.structName = structName;
    result.isVarargs = isVarargs;
    result.arrayDimension = arrayDimension;
    return result;
  }

  private AstType parseType(Parser parser) {
    if (isEndOfTokens(parser)) {
      reportEndOfFileError(parser, "expecting a type, but end of file has been reached.");
    }

    Token maybeVarargs = peekToken(parser);
    boolean isVarargs = matches(maybeVarargs, "..");
    if (isVarargs) eatToken(parser);

    int arrayDimensions = 0;
    while (!isEndOfTokens(parser)) {
      Token maybeStartBracket = peekToken(parser);
      if (!matches(maybeStartBracket, "[")) break;
      eatToken(parser);

      expectToken(parser, "]");
      arrayDimensions += 1;
    }

    // void-type is only valid for function return types as implicit return type.
    // maybe we would like to allow to return void explicitly: fn :: () -> void {}

    Token token = eatToken(parser);

    if (matches(token, "i8")) return astType_primitive(AstTypeCategory.I8, isVarargs, arrayDimensions);
    if (matches(token, "i16")) return astType_primitive(AstTypeCategory.I16, isVarargs, arrayDimensions);
    if (matches(token, "i32")) return astType_primitive(AstTypeCategory.I32, isVarargs, arrayDimensions);
    if (matches(token, "i64")) return astType_primitive(AstTypeCategory.I64, isVarargs, arrayDimensions);
    if (matches(token, "f32")) return astType_primitive(AstTypeCategory.F32, isVarargs, arrayDimensions);
    if (matches(token, "f64")) return astType_primitive(AstTypeCategory.F64, isVarargs, arrayDimensions);

    if (matches(token, "string")) return astType_primitive(AstTypeCategory.String, isVarargs, arrayDimensions);

    if (matches(token, "bool")) return astType_primitive(AstTypeCategory.Bool, isVarargs, arrayDimensions);
    if (matches(token, "char")) return astType_primitive(AstTypeCategory.Char, isVarargs, arrayDimensions);

    if (matches(token, "any")) return astType_primitive(AstTypeCategory.Any, isVarargs, arrayDimensions);

    if (matches(token, TokenType.Identifier)) return astType_struct(token.value, isVarargs, arrayDimensions);

    reportError(parser, token, "expected a type but found \"%s\" (%s).", token.value, token.type);
    return null;
  }

  private AstFunction parseFunction(Parser parser) {
    AstFunction result = new AstFunction();
    result.parameters = new ArrayList<>();
    result.bodyStatements = new ArrayList<>();

    Token functionName = expectToken(parser, TokenType.Identifier);
    result.name = functionName.value;
    result.location = new Location();

    result.location.lineStart = functionName.location.lineStart;
    result.location.charStart = functionName.location.charStart;

    expectToken(parser, "::");
    expectToken(parser, "(");

    // parse function parameters
    boolean isFirstParameter = true;
    while (true) {

      if (isEndOfTokens(parser)) {
        reportEndOfFileError(parser, "function parameter declaration is not complete, but end of file has been reached.");
      }

      Token token = peekToken(parser);
      if (matches(token, ")")) break;

      if (!isFirstParameter) expectToken(parser, ",");
      isFirstParameter = false;

      Token parameterName = expectToken(parser, TokenType.Identifier);
      expectToken(parser, ":");

      AstType type = parseType(parser);

      AstParameterDeclaration parameter = new AstParameterDeclaration();
      parameter.location = parameterName.location;
      parameter.name = parameterName.value;
      parameter.type = type;
      result.parameters.add(parameter);
    }

    expectToken(parser, ")");

    // parse return value
    if (isEndOfTokens(parser)) {
      reportEndOfFileError(parser, "function header is not complete, but end of file has been reached.");
    }

    Token headerReturnKeyword = peekToken(parser);

    if (matches(headerReturnKeyword, "->")) {
      eatToken(parser);

      // @TODO: handle multiple return values

      AstType returnType = parseType(parser);
      result.returnType = returnType;

    } else {
      result.returnType = astType_primitive(AstTypeCategory.Void, false, 0);
    }

    Token maybeFromJavaLibrary = peekToken(parser);
    if (matches(maybeFromJavaLibrary, "#lib")) {
      eatToken(parser);

      Token literalToken = peekToken(parser);
      if (literalToken.literalType != TokenLiteralType.String) reportError(parser, literalToken, "expected string literal.");
      eatToken(parser);

      Token endToken = expectToken(parser, ";");

      result.hasJavaLibraryBinding = true;
      result.javaLibraryName = literalToken.value;

      result.location.lineEnd = endToken.location.lineEnd;
      result.location.charEnd = endToken.location.charEnd;

      return result;
    }

    Token openScopeBracket = expectToken(parser, "{");
    result.location.lineEnd = openScopeBracket.location.lineEnd;
    result.location.charEnd = openScopeBracket.location.charEnd;

    while (true) {
      if (isEndOfTokens(parser)) reportEndOfFileError(parser, "function body is not complete, but end of file has been reached. Did you forget a \"}\"?");

      Token bodyEndToken = peekToken(parser);
      if (matches(bodyEndToken, "}")) break;

      AstStatement statement = parseStatement(parser);
      result.bodyStatements.add(statement);
    }

    expectToken(parser, "}");

    return result;
  }

  private AstStatement parseStatement(Parser parser) {
    if (isDeclaration(parser)) {
      return parseDeclaration(parser);
    }

    if (isDefinition(parser)) {
      return parseDefinition(parser);
    }

    if (isReturn(parser)) {
      return parseReturn(parser);
    }

    if (isFunctionCall(parser)) {
      AstFunctionCall functionCall = parseFunctionCall(parser);
      expectToken(parser, ";");
      return functionCall;
    }

    if (isIfStatement(parser)) {
      return parseIfStatement(parser);
    }

    if (isWhileLoop(parser)) {
      return parseWhileLoop(parser);
    }

    Token token = peekToken(parser);
    reportError(parser, token, "unexpected token \"%s\" in a function body. Did you forget a \"}\" before this token?", token.value);
    return null;
  }

  private AstWhileLoop parseWhileLoop(Parser parser) {
    AstWhileLoop result = new AstWhileLoop();
    result.body = new ArrayList<>();

    expectToken(parser, "while");
    expectToken(parser, "(");
    AstExpression condition = parseExpression(parser);
    result.condition = condition;
    expectToken(parser, ")");

    expectToken(parser, "{");
    while (true) {
      if (isEndOfTokens(parser)) {
        reportEndOfFileError(parser, "while-loop is not complete, but end of file has been reached. Did you forget a \"}\"?");
      }

      Token token = peekToken(parser);
      if (matches(token, "}")) break;

      AstStatement statement = parseStatement(parser);
      result.body.add(statement);
    }
    expectToken(parser, "}");

    return result;
  }

  private boolean isWhileLoop(Parser parser) {
    Token token = peekToken(parser);
    if (matches(token, "while")) return true;
    return false;
  }

  private AstIfStatement parseIfStatement(Parser parser) {
    AstIfStatement result = new AstIfStatement();
    result.ifBody = new ArrayList<>();
    result.elseBody = new ArrayList<>();

    expectToken(parser, "if");
    expectToken(parser, "(");
    AstExpression condition = parseExpression(parser);
    result.condition = condition;

    expectToken(parser, ")");
    expectToken(parser, "{");

    while (true) {

      if (isEndOfTokens(parser)) {
        reportEndOfFileError(parser, "if-statement body is not complete (missing \"}\")), but end of file has been reached.");
      }

      Token token = peekToken(parser);
      if (matches(token, "}")) break;

      AstStatement statement = parseStatement(parser);
      result.ifBody.add(statement);
    }

    expectToken(parser, "}");

    if (isEndOfTokens(parser)) return result;

    Token maybeElse = peekToken(parser);
    if (matches(maybeElse, "else")) {
      expectToken(parser, "else");
      expectToken(parser, "{");

      while (true) {

        if (isEndOfTokens(parser)) {
          reportEndOfFileError(parser, "else-statement body is not complete (missing \"}\")), but end of file has been reached.");
        }

        Token token = peekToken(parser);
        if (matches(token, "}")) break;

        AstStatement statement = parseStatement(parser);
        result.elseBody.add(statement);
      }

      expectToken(parser, "}");
    }

    return result;
  }

  private boolean isIfStatement(Parser parser) {
    Token token = peekToken(parser);
    if (matches(token, "if")) return true;
    return false;
  }

  private AstFunctionCall parseFunctionCall(Parser parser) {
    AstFunctionCall result = new AstFunctionCall();
    result.location = new Location();
    result.arguments = new ArrayList<>();

    Token functionName = expectToken(parser, TokenType.Identifier);
    result.name = functionName.value;

    result.location.lineStart = functionName.location.lineStart;
    result.location.charStart = functionName.location.charStart;

    expectToken(parser, "(");

    boolean isFirstArgument = true;
    while (true) {

      if (isEndOfTokens(parser)) {
        reportEndOfFileError(parser, "function call argument list is not complete, but end of file has been reached.");
      }

      Token token = peekToken(parser);
      if (matches(token, ")")) break;

      if (!isFirstArgument) expectToken(parser, ",");
      isFirstArgument = false;

      AstExpression argument = parseExpression(parser, 0, true, false);
      result.arguments.add(argument);
    }

    Token closingParenthesis = expectToken(parser, ")");

    result.location.lineEnd = closingParenthesis.location.lineEnd;
    result.location.charEnd = closingParenthesis.location.charEnd;

    return result;
  }

  private boolean isFunctionCall(Parser parser) {
    Token token1 = peekToken(parser);
    if (!matches(token1, TokenType.Identifier)) return false;

    Token token2 = peekAhead(parser, 1);
    if (!matches(token2, "(")) return false;

    return true;
  }

  private AstReturn parseReturn(Parser parser) {
    AstReturn result = new AstReturn();

    Token returnToken = expectToken(parser, "return");
    result.location = location_copy(returnToken.location);

    Token maybeSemiColon = peekToken(parser);
    if (matches(maybeSemiColon, ";")) {
      eatToken(parser);
      return result;
    }

    result.returnExpression = parseExpression(parser);
    expectToken(parser, ";");

    return result;
  }

  default Location location_copy(Location location) {
    return location(location.lineStart, location.charStart, location.lineEnd, location.charEnd);
  }

  private boolean isReturn(Parser parser) {
    Token token = peekToken(parser);
    if (!matches(token, "return")) return false;
    return true;
  }

  private boolean isComment(Parser parser) {
    Token token = peekToken(parser);
    if (!matches(token, TokenType.Comment)) return false;
    return true;
  }

  private AstDefinition parseDefinition(Parser parser) {
    AstDefinition result = new AstDefinition();

    AstVariable identifier = parseVariable(parser);
    result.lhs = identifier;

    expectToken(parser, "=");

    AstAssignment assignment = parseAssignment(parser);
    result.rhs = assignment;

    expectToken(parser, ";");

    return result;
  }

  private AstExpression parseExpression(Parser parser) {
    return parseExpression(parser, 0f, false, false);
  }

  static public class BindingPower {
    public float left, right;
  }

  private BindingPower bindingPower(float left, float right) {
    BindingPower result = new BindingPower();
    result.left = left;
    result.right = right;
    return result;
  }

  private BindingPower getInfixBindingPower(String operator) {
    if (operator.equals("%")) return bindingPower(20f, 20.1f);
    if (operator.equals("*")) return bindingPower(20f, 20.1f);
    if (operator.equals("/")) return bindingPower(20f, 20.1f);

    if (operator.equals("+")) return bindingPower(16f, 16.1f);
    if (operator.equals("-")) return bindingPower(16f, 16.1f);

    if (operator.equals(">>")) return bindingPower(14f, 14.1f);
    if (operator.equals(">>>")) return bindingPower(14f, 14.1f);
    if (operator.equals("<<")) return bindingPower(14f, 14.1f);

    if (operator.equals("==")) return bindingPower(13f, 13.1f);
    if (operator.equals(">=")) return bindingPower(13f, 13.1f);
    if (operator.equals("<=")) return bindingPower(13f, 13.1f);
    if (operator.equals(">")) return bindingPower(13f, 13.1f);
    if (operator.equals("<")) return bindingPower(13f, 13.1f);

    if (operator.equals("==")) return bindingPower(12f, 12.1f);
    if (operator.equals("!=")) return bindingPower(12f, 12.1f);

    if (operator.equals("&")) return bindingPower(11f, 11.1f);
    if (operator.equals("^")) return bindingPower(10f, 10.1f);
    if (operator.equals("|")) return bindingPower(9f, 9.1f);

    if (operator.equals("&&")) return bindingPower(8f, 8.1f);
    if (operator.equals("||")) return bindingPower(7f, 7.1f);

    return null;
  }

  private float getTypeCastPrefixBindingPower() {
    return 100f;
  }

  private float getPrefixBindingPower(AstUnaryOperator unaryOperator) {
    if (unaryOperator.operator.equals("-")) return 21.1f;
    if (unaryOperator.operator.equals("+")) return 21.1f;
    if (unaryOperator.operator.equals("!")) return 21.1f;
    if (unaryOperator.operator.equals("~")) return 21.1f;
    return -1;
  }

  private AstVariable parseVariable(Parser parser) {
    AstVariable result = new AstVariable();

    Token name = expectToken(parser, TokenType.Identifier);
    result.name = name.value;
    result.location = new Location();
    result.arrayExpressions = new ArrayList<>();

    result.location.lineStart = name.location.lineStart;
    result.location.charStart = name.location.charStart;
    result.location.lineEnd = name.location.lineEnd;
    result.location.charEnd = name.location.charEnd;

    // a[0][1+2][fn()]
    while (!isEndOfTokens(parser)) {
      Token startBracket = peekToken(parser);
      if (!matches(startBracket, "[")) break;
      eatToken(parser);

      AstExpression expression = parseExpression(parser);
      result.arrayExpressions.add(expression);
      Token end = expectToken(parser, "]");

      result.location.lineEnd = end.location.lineEnd;
      result.location.charEnd = end.location.charEnd;
    }

    // a.b
    if (isEndOfTokens(parser)) return result;

    Token dot = peekToken(parser);
    if (!matches(dot, ".")) return result;
    eatToken(parser);

    AstVariable child = parseVariable(parser);
    result.child = child;

    return result;
  }

  // https://matklad.github.io/2020/04/13/simple-but-powerful-pratt-parsing.html#From-Precedence-to-Binding-Power
  private AstExpression parseExpression(Parser parser, float minimumBindingPower, boolean isFunctionCallArgument, boolean isParentUnaryOperator) {
    if (isEndOfTokens(parser)) reportEndOfFileError(parser, "expression is not complete, but end of file has been reached.");

    Token lhsToken = peekToken(parser);

    AstExpression lhs = null;
    if (matches(lhsToken, TokenType.Literal)) {
      eatToken(parser);

      AstLiteral literal = astLiteral(lhsToken.value, astType_from_token(lhsToken.literalType, false, 0), lhsToken.location);
      lhs = literal;

    } else if (matches(lhsToken, "(")) {

      if (isTypeCast(parser)) {
        expectToken(parser, "(");
        AstType type = parseType(parser);
        expectToken(parser, ")");

        float bindingPower = getTypeCastPrefixBindingPower();

        AstTypeCast typecast = new AstTypeCast();
        typecast.type = type;
        typecast.expression = parseExpression(parser, bindingPower, isFunctionCallArgument, false);
        typecast.implicit = false;

        lhs = typecast;

      } else {
        // parenthesis 
        expectToken(parser, "(");
        AstExpression body = parseExpression(parser, 0, isFunctionCallArgument, false);
        expectToken(parser, ")");

        AstParenthesis parenthesis = new AstParenthesis();
        parenthesis.body = body;
        lhs = parenthesis;
      }

    } else if (matches(lhsToken, TokenType.Identifier)) {

      if (isFunctionCall(parser)) {
        AstFunctionCall functionCall = parseFunctionCall(parser);
        lhs = functionCall;

      } else {
        AstVariable variable = parseVariable(parser);
        lhs = variable;
      }

    } else if (matches(lhsToken, TokenType.Operator)) {
      Token operatorToken = expectToken(parser, TokenType.Operator);

      if (isParentUnaryOperator) reportError(parser, lhsToken, "an unary operator can't procede another unary operator. Consider adding parenthesis.");

      AstUnaryOperator unaryMinus = new AstUnaryOperator();
      unaryMinus.operator = lhsToken.value;

      float bindingPower = getPrefixBindingPower(unaryMinus);
      if (bindingPower == -1) reportError(parser, operatorToken, "Operator \"%s\" is not a unary operator.", operatorToken.value);

      AstExpression rhs = parseExpression(parser, bindingPower, isFunctionCallArgument, true);
      unaryMinus.body = rhs;

      lhs = unaryMinus;

    } else {
      reportError(parser, lhsToken, "unexpected start token in expression.");
    }

    while (true) {

      if (isEndOfTokens(parser)) {
        reportEndOfFileError(parser, "expression is not complete, but end of file has been reached. Did you forget a \";\"?");
      }

      Token tokenOperator = peekToken(parser);

      // only for "new [10]"
      if (matches(tokenOperator, "]")) break;

      // only for function call arguments
      if (isFunctionCallArgument && matches(tokenOperator, ",")) break;

      if (matches(tokenOperator, ";")) break;
      if (matches(tokenOperator, ")")) break;

      BindingPower infixBindingPower = getInfixBindingPower(tokenOperator.value);
      if (infixBindingPower != null) {

        if (infixBindingPower.left < minimumBindingPower) break;
        eatToken(parser);

        AstExpression rhs = parseExpression(parser, infixBindingPower.right, isFunctionCallArgument, false);

        AstBinaryOperator operator = new AstBinaryOperator();
        operator.operator = tokenOperator.value;
        operator.lhs = lhs;
        operator.rhs = rhs;

        lhs = operator;

      } else {
        reportError(parser, tokenOperator, "expected a infix operator, but \"%s\" is not.", tokenOperator.value);
      }
    }

    return lhs;
  }

  private boolean isTypeCast(Parser parser) {
    Token token1 = peekToken(parser);
    if (!matches(token1, "(")) return false;

    Token token2 = peekAhead(parser, 1);
    if (!(matches(token2, TokenType.Keyword) || matches(token2, TokenType.Identifier))) return false;

    Token token3 = peekAhead(parser, 2);
    if (!matches(token3, ")")) return false;

    return true;
  }

  private boolean isDefinition(Parser parser) {
    Token token1 = peekToken(parser);
    if (!matches(token1, TokenType.Identifier)) return false;

    int ahead = 1;
    while (true) {
      Token tokenAt = peekAhead(parser, ahead);
      ahead += 1;

      if (tokenAt == null) return false;

      if (matches(tokenAt, "=")) return true;

      if (matches(tokenAt, ".")) {
        Token token2 = peekAhead(parser, ahead);
        ahead += 1;

        if (token2 == null) return false;
        if (!matches(token2, TokenType.Identifier)) return false;

      } else if (matches(tokenAt, "[")) {
        int leftBracketSurplus = 1;

        while (true) {
          Token token = peekAhead(parser, ahead);
          ahead += 1;
          if (token == null) return false;

          if (matches(token, "[")) {
            leftBracketSurplus += 1;

          } else if (matches(token, "]")) {
            leftBracketSurplus -= 1;

            if (leftBracketSurplus == 0) break;
          }
        }
      } else {
        return false;
      }
    }
  }

  private AstDeclaration parseDeclaration(Parser parser) {
    AstDeclaration result = new AstDeclaration();

    Token identifier = expectToken(parser, TokenType.Identifier);
    result.identifier = identifier.value;

    result.location = identifier.location;

    expectToken(parser, ":");

    AstType type = parseType(parser);
    result.type = type;

    if (isEndOfTokens(parser)) reportEndOfFileError(parser, "declaration is not complete, but end of file reached.");
    Token assignmentToken = peekToken(parser);
    if (matches(assignmentToken, "=")) {
      eatToken(parser);

      AstAssignment assignment = parseAssignment(parser);
      result.optionalInit = assignment;
    }

    expectToken(parser, ";");

    return result;
  }

  private AstAssignment parseAssignment(Parser parser) {
    if (isEndOfTokens(parser)) reportEndOfFileError(parser, "assignment is not complete, but end of file reached.");
    Token expressionOrNew = peekToken(parser);

    if (matches(expressionOrNew, "new")) {
      expectToken(parser, "new");
      AstNew _new = new AstNew();
      _new.location = new Location();
      _new.arraySizes = new ArrayList<>();

      _new.location.lineStart = expressionOrNew.location.lineStart;
      _new.location.charStart = expressionOrNew.location.charStart;
      _new.location.lineEnd = expressionOrNew.location.lineEnd;
      _new.location.charEnd = expressionOrNew.location.charEnd;

      while (!isEndOfTokens(parser)) {
        Token maybeArray = peekToken(parser);
        if (!matches(maybeArray, "[")) break;
        eatToken(parser);

        AstExpression expression = parseExpression(parser);
        _new.arraySizes.add(expression);

        Token closeBracket = expectToken(parser, "]");
        _new.location.lineEnd = closeBracket.location.lineEnd;
        _new.location.charEnd = closeBracket.location.charEnd;
      }

      return _new;
    }

    return parseExpression(parser);
  }

  private AstStructField parseField(Parser parser) {
    AstStructField result = new AstStructField();
    result.location = new Location();

    Token identifier = expectToken(parser, TokenType.Identifier);
    result.name = identifier.value;

    result.location.lineStart = identifier.location.lineStart;
    result.location.charStart = identifier.location.charStart;

    expectToken(parser, ":");

    AstType type = parseType(parser);
    result.type = type;

    Token endToken = expectToken(parser, ";");

    result.location.lineEnd = endToken.location.lineEnd;
    result.location.charEnd = endToken.location.charEnd;

    return result;
  }

  private boolean isDeclaration(Parser parser) {
    Token token1 = peekToken(parser);
    if (!matches(token1, TokenType.Identifier)) return false;

    Token token2 = peekAhead(parser, 1);
    if (!matches(token2, ":")) return false;

    Token token3 = peekAhead(parser, 2);
    if (!(matches(token3, TokenType.Identifier) || matches(token3, TokenType.Keyword) || matches(token3, "["))) return false;

    return true;
  }

  private Token expectToken(Parser parser, String value) {
    if (isEndOfTokens(parser)) {
      reportEndOfFileError(parser, "expected \"%s\", but end of file has been reached.", value);
    }

    Token token = eatToken(parser, value);
    if (token == null) {
      Token found = peekToken(parser);
      reportError(parser, found, "expected \"%s\" but found \"%s\".", value, found.value);
    }

    return token;
  }

  private Token expectToken(Parser parser, TokenType type) {

    if (isEndOfTokens(parser)) {
      reportEndOfFileError(parser, "expected \"%s\", but end of file reached.", type);
    }

    Token token = eatToken(parser, type);
    if (token == null) {
      Token found = peekToken(parser);
      reportError(parser, found, "expected %s but found %s (\"%s\").", type, found.type, found.value);
    }

    return token;
  }

  default String tokenToString(Token token) {
    return String.format("[%s, \"%s\" %s [%d,%d]-[%d,%d]]", token.type, token.value, token.literalType, token.location.lineStart, token.location.charStart, token.location.lineEnd, token.location.charEnd);
  }

  private Token eatToken(Parser parser, String value) {
    Token token = peekToken(parser);
    if (!matches(token, value)) return null;
    eatToken(parser);
    return token;
  }

  private Token eatToken(Parser parser, TokenType type) {
    Token token = peekToken(parser);
    if (!matches(token, type)) return null;
    eatToken(parser);
    return token;
  }

  private Token eatToken(Parser parser) {
    Token token = peekToken(parser);
    parser.at += 1;
    return token;
  }

  default void assertIt(boolean condition) {
    if (condition) return;
    throw new CompilerException("Assertion failure");
  }

  private void reportEndOfFileError(Parser parser, String format, Object... args) {
    Token token = parser.tokens.get(parser.tokens.size() - 1);
    reportError(parser.sourceCode, token.location, format, args);
  }

  private void reportError(Parser parser, Token token, String format, Object... args) {
    reportError(parser.sourceCode, token.location, format, args);
  }

  private void reportError(Lexer lexer, String format, Object... args) {
    Location location = location(lexer.cursor.lineNumber, lexer.cursor.characterNumber, lexer.cursor.lineNumber, lexer.cursor.characterNumber + 1);
    reportError(lexer.sourceCode, location, format, args);
  }

  default Location getLocation(Object astNode) {
    if (astNode instanceof AstNew) {
      AstNew _new = (AstNew) astNode;
      return _new.location;
    }

    if (astNode instanceof AstReturn) {
      AstReturn _return = (AstReturn) astNode;
      return _return.location;
    }

    if (astNode instanceof AstFunctionCall) {
      AstFunctionCall call = (AstFunctionCall) astNode;
      return call.location;
    }

    if (astNode instanceof AstDefinition) {
      AstDefinition defn = (AstDefinition) astNode;
      return getLocation(defn.lhs);
    }

    if (astNode instanceof AstDeclaration) {
      AstDeclaration decl = (AstDeclaration) astNode;
      return decl.location;
    }

    if (astNode instanceof AstParameterDeclaration) {
      AstParameterDeclaration variable = (AstParameterDeclaration) astNode;
      return variable.location;
    }

    if (astNode instanceof AstVariable) {
      AstVariable variable = (AstVariable) astNode;
      return variable.location;
    }

    if (astNode instanceof AstFunction) {
      AstFunction function = (AstFunction) astNode;
      return function.location;
    }

    if (astNode instanceof AstStruct) {
      AstStruct struct = (AstStruct) astNode;
      return struct.location;
    }

    if (astNode instanceof AstStructField) {
      AstStructField field = (AstStructField) astNode;
      return field.location;
    }

    if (astNode instanceof AstLiteral) {
      AstLiteral literal = (AstLiteral) astNode;
      return literal.location;
    }

    if (astNode instanceof AstUnaryOperator) {
      AstUnaryOperator unaryOperator = (AstUnaryOperator) astNode;
      Location location = getLocation(unaryOperator.body);
      return location;
    }

    if (astNode instanceof AstBinaryOperator) {
      AstBinaryOperator binaryOperator = (AstBinaryOperator) astNode;

      Location lhs = getLocation(binaryOperator.lhs);
      Location rhs = getLocation(binaryOperator.rhs);

      Location location = new Location();
      location.lineStart = lhs.lineStart;
      location.charStart = lhs.charStart;
      location.lineEnd = rhs.lineEnd;
      location.charEnd = rhs.charEnd;

      return location;
    }

    throw new CompilerException("didn't handle: %s", astNode.getClass().getSimpleName());
  }

  private String indent(String string, String prefix) {
    StringBuilder builder = new StringBuilder();

    char[] charSequence = string.toCharArray();
    if (charSequence.length > 0) builder.append(prefix);

    for (char c : charSequence) {
      builder.append(c);
      if (c == '\n') builder.append(prefix);
    }

    return builder.toString();
  }

  private String repeatString(String sequence, int numRepeats) {
    StringBuilder builder = new StringBuilder();

    for (int i = 0; i < numRepeats; i++) {
      builder.append(sequence);
    }

    return builder.toString();
  }

  default void reportError(SourceFile file, Location location, String messageFormat, Object... messageArgs) {
    StringBuilder builder = new StringBuilder();

    String fileHeader = String.format("File \"%s\"", file.filename);
    String lineLocation = (location.lineStart != location.lineEnd) ? String.format("%d-%d", location.lineStart, location.lineEnd) : String.format("%d", location.lineStart);

    int previousLinesToShow = 0;
    int startIndex = findIndexFromLineAndChar(file.content, location.lineStart - previousLinesToShow, 1);
    int endIndex = findIndexFromLineAndChar(file.content, location.lineEnd, -1);

    String codeSnippet = file.content.substring(startIndex, endIndex + 1);
    codeSnippet = codeSnippet.stripTrailing();  // remove '\r' if we are on windows

    int spacesInIndent = 2;
    String indentPrefix = repeatString(" ", spacesInIndent);
    String indentedCodeSnippet = indent(codeSnippet, indentPrefix);

    builder.append(fileHeader);
    builder.append(", line ");
    builder.append(lineLocation);
    builder.append("\n");
    builder.append(indentedCodeSnippet);
    builder.append("\n");

    int highlighSpaceCount = location.charStart - 1 + indentPrefix.length();
    builder.append(repeatString(" ", highlighSpaceCount));

    int markerCount = location.charEnd - location.charStart;
    builder.append(repeatString("^", markerCount));

    builder.append("\n");

    builder.append("Error: ");
    String message = String.format(messageFormat, messageArgs);
    builder.append(message);

    reportError(builder.toString());
  }

  private int findIndexFromLineAndChar(String input, int lineNumber, int lineCharacterNumber) {
    if (lineNumber <= 0) return 1;

    int lineNumAt = 1;
    int lineCharAt = 1;

    int at = 0;

    int inputSize = input.length();
    while (at < inputSize) {

      if (lineNumAt == lineNumber && lineCharAt == lineCharacterNumber) {
        return at;
      }

      lineCharAt += 1;

      char c = input.charAt(at);
      if (c == '\n') {
        lineCharAt = 1;
        lineNumAt += 1;
      }

      if (lineCharacterNumber == -1 && lineNumAt > lineNumber) {
        return at;
      }

      at += 1;
    }

    return inputSize - 1;
  }

  private void reportError(String message) {
    if (CompilerModule.settings.catchableErrors) {
      throw new UserException("\n%s", message);
    } else {
      System.out.printf("%s", message);
      System.exit(0);
    }
  }

  private boolean matches(Token token, String value) {
    return token != null && value.equals(token.value);
  }

  private boolean matches(Token token, TokenType type) {
    return token != null && token.type == type;
  }

  private Token peekToken(Parser parser) {
    return peekAhead(parser, 0);
  }

  private boolean isEndOfTokens(Parser parser) {
    return parser.at >= parser.tokens.size();
  }

  static public class Cursor {
    public int at;
    public int lineNumber;
    public int characterNumber;
  }

  static public class SourceFile {
    public String filename;
    public String content;
  }

  static public class Lexer {
    public SourceFile sourceCode;
    public Cursor cursor;
    public Cursor checkpoint;
  }

  static public enum TokenType {
    Identifier, Keyword, Operator, Literal, Comment, Seperator;
  }

  static public class Location {
    public int lineStart, lineEnd;
    public int charStart, charEnd;
  }

  private Location location(int lineStart, int charStart, int lineEnd, int charEnd) {
    Location result = new Location();
    result.lineStart = lineStart;
    result.charStart = charStart;
    result.lineEnd = lineEnd;
    result.charEnd = charEnd;
    return result;
  }

  static public class Token {
    public Location location;
    public TokenType type;
    public String value;
    public TokenLiteralType literalType;
  }

  private Token token(TokenType type, String value, TokenLiteralType literalType, Location location) {
    Token result = new Token();
    result.type = type;
    result.value = value;
    result.location = location;
    result.literalType = literalType;
    return result;
  }

  private Token token_identifier(String value, Location location) {
    return token(TokenType.Identifier, value, null, location);
  }

  private Token token_keyword(String value, Location location) {
    return token(TokenType.Keyword, value, null, location);
  }

  private Token token_seperator(String value, Location location) {
    return token(TokenType.Seperator, value, null, location);
  }

  private Token token_comment(String comment, Location location) {
    return token(TokenType.Comment, comment, null, location);
  }

  private Token token_operator(String value, Location location) {
    return token(TokenType.Operator, value, null, location);
  }

  private Token token_literal(String value, TokenLiteralType literalType, Location location) {
    return token(TokenType.Literal, value, literalType, location);
  }

  default List<Token> lex(SourceFile sourceCode) {
    List<Token> result = new ArrayList<>();

    Lexer lexer = new Lexer();
    lexer.sourceCode = sourceCode;

    lexer.cursor = new Cursor();
    lexer.cursor.characterNumber = 1;
    lexer.cursor.lineNumber = 1;

    lexer.checkpoint = new Cursor();
    lexer.checkpoint.characterNumber = 1;
    lexer.checkpoint.lineNumber = 1;

    while (!isEndOfFile(lexer)) {
      eatWhitespaces(lexer);
      if (isEndOfFile(lexer)) break;

      Token token = findAndEatNextToken(lexer);
      if (token.type == TokenType.Comment) continue;  // ignore comments at lexing to simplify parsing.

      result.add(token);
    }

    return result;
  }

  private Token eatComment(Lexer lexer) {
    Token singleLine = eatSingleLineComment(lexer);
    if (singleLine != null) return singleLine;

    Token multiLine = eatMultiLineComment(lexer);
    if (multiLine != null) return multiLine;

    return null;
  }

  private Token eatMultiLineComment(Lexer lexer) {
    if (!matchThenEat(lexer, "/*")) return null;

    int nestingLevel = 1;

    while (!isEndOfFile(lexer)) {
      if (nestingLevel == 0) break;

      if (matchThenEat(lexer, "/*")) nestingLevel += 1;
      else if (matchThenEat(lexer, "*/")) nestingLevel -= 1;
      else eatCharacter(lexer);
    }

    if (nestingLevel != 0) {
      recoverCursorCheckpoint(lexer);
      return null;
    }

    String comment = getStringSinceCursorCheckpoint(lexer);
    Token result = token_comment(comment, consume(lexer));

    return result;
  }

  private Token eatSingleLineComment(Lexer lexer) {
    if (!matches(lexer, "//")) return null;

    while (!isEndOfFile(lexer)) {
      char c = peekCharacter(lexer);
      if (c == '\r' || c == '\n') break;
      eatCharacter(lexer);
    }

    String comment = getStringSinceCursorCheckpoint(lexer);
    Token result = token_comment(comment, consume(lexer));

    return result;
  }

  private Location consume(Lexer lexer) {
    Location result = location(lexer.checkpoint.lineNumber, lexer.checkpoint.characterNumber, lexer.cursor.lineNumber, lexer.cursor.characterNumber);
    clearCheckpoint(lexer);
    return result;
  }

  private Token findAndEatNextToken(Lexer lexer) {
    Token comment = eatComment(lexer);
    if (comment != null) return comment;

    // numbers may start with the decimal, e.g. ".8f", so tokenize here.
    Token floatingPoint = eatFloatingPointLiteral(lexer);
    if (floatingPoint != null) return floatingPoint;

    if (eatSymbols(lexer, "::")) return token_seperator("::", consume(lexer));
    if (eatSymbols(lexer, ":")) return token_seperator(":", consume(lexer));

    if (eatSymbols(lexer, "..")) return token_seperator("..", consume(lexer));
    if (eatSymbols(lexer, ".")) return token_seperator(".", consume(lexer));

    if (eatSymbols(lexer, ",")) return token_seperator(",", consume(lexer));
    if (eatSymbols(lexer, "->")) return token_seperator("->", consume(lexer));
    if (eatSymbols(lexer, "(")) return token_seperator("(", consume(lexer));
    if (eatSymbols(lexer, ")")) return token_seperator(")", consume(lexer));
    if (eatSymbols(lexer, "{")) return token_seperator("{", consume(lexer));
    if (eatSymbols(lexer, "}")) return token_seperator("}", consume(lexer));
    if (eatSymbols(lexer, ";")) return token_seperator(";", consume(lexer));

    if (eatSymbols(lexer, "[")) return token_operator("[", consume(lexer));
    if (eatSymbols(lexer, "]")) return token_operator("]", consume(lexer));

    // common math operator
    if (eatSymbols(lexer, "+")) return token_operator("+", consume(lexer));
    if (eatSymbols(lexer, "-")) return token_operator("-", consume(lexer));
    if (eatSymbols(lexer, "*")) return token_operator("*", consume(lexer));
    if (eatSymbols(lexer, "/")) return token_operator("/", consume(lexer));
    if (eatSymbols(lexer, "%")) return token_operator("%", consume(lexer));

    // bitwise shift operators
    if (eatSymbols(lexer, "<<")) return token_operator("<<", consume(lexer));
    if (eatSymbols(lexer, ">>>")) return token_operator(">>>", consume(lexer));
    if (eatSymbols(lexer, ">>")) return token_operator(">>", consume(lexer));

    // relational operators
    if (eatSymbols(lexer, "==")) return token_operator("==", consume(lexer));
    if (eatSymbols(lexer, ">=")) return token_operator(">=", consume(lexer));
    if (eatSymbols(lexer, "<=")) return token_operator("<=", consume(lexer));
    if (eatSymbols(lexer, "<")) return token_operator("<", consume(lexer));
    if (eatSymbols(lexer, ">")) return token_operator(">", consume(lexer));

    // equality
    if (eatSymbols(lexer, "==")) return token_operator("==", consume(lexer));
    if (eatSymbols(lexer, "!=")) return token_operator("!=", consume(lexer));

    // logical operators
    if (eatSymbols(lexer, "!")) return token_operator("!", consume(lexer));
    if (eatSymbols(lexer, "&&")) return token_operator("&&", consume(lexer));
    if (eatSymbols(lexer, "||")) return token_operator("||", consume(lexer));

    // bitwise operators
    if (eatSymbols(lexer, "~")) return token_operator("~", consume(lexer));
    if (eatSymbols(lexer, "&")) return token_operator("&", consume(lexer));
    if (eatSymbols(lexer, "^")) return token_operator("^", consume(lexer));
    if (eatSymbols(lexer, "|")) return token_operator("|", consume(lexer));

    // assignment
    if (eatSymbols(lexer, "=")) return token_operator("=", consume(lexer));

    if (eatWord(lexer, "nil")) return token_literal("nil", TokenLiteralType.Object, consume(lexer));
    if (eatWord(lexer, "new")) return token_keyword("new", consume(lexer));

    if (eatWord(lexer, "#lib")) return token_keyword("#lib", consume(lexer));

    if (eatWord(lexer, "return")) return token_keyword("return", consume(lexer));
    if (eatWord(lexer, "if")) return token_keyword("if", consume(lexer));
    if (eatWord(lexer, "else")) return token_keyword("else", consume(lexer));
    if (eatWord(lexer, "while")) return token_keyword("while", consume(lexer));
    if (eatWord(lexer, "struct")) return token_keyword("struct", consume(lexer));

    if (eatWord(lexer, "string")) return token_keyword("string", consume(lexer));

    if (eatWord(lexer, "bool")) return token_keyword("bool", consume(lexer));
    if (eatWord(lexer, "i8")) return token_keyword("i8", consume(lexer));
    if (eatWord(lexer, "i16")) return token_keyword("i16", consume(lexer));
    if (eatWord(lexer, "i32")) return token_keyword("i32", consume(lexer));
    if (eatWord(lexer, "i64")) return token_keyword("i64", consume(lexer));

    if (eatWord(lexer, "f32")) return token_keyword("f32", consume(lexer));
    if (eatWord(lexer, "f64")) return token_keyword("f64", consume(lexer));

    if (eatWord(lexer, "char")) return token_keyword("char", consume(lexer));

    if (eatWord(lexer, "any")) return token_keyword("any", consume(lexer));

    Token integer = eatIntegerLiteral(lexer);
    if (integer != null) return integer;

    Token string = eatStringLiteral(lexer);
    if (string != null) return string;

    Token character = eatCharLiteral(lexer);
    if (character != null) return character;

    Token literal = eatBooleanLiteral(lexer);
    if (literal != null) return literal;

    Token identifier = eatIdentifier(lexer);
    if (identifier != null) return identifier;

    reportError(lexer, "invalid syntax \"%c\".", peekCharacter(lexer));
    return null;
  }

  private Token eatStringLiteral(Lexer lexer) {
    if (isEndOfFile(lexer)) return null;

    char c1 = peekCharacter(lexer);
    if (c1 != '\"') return null;
    eatCharacter(lexer);

    boolean foundEndQuotes = false;
    boolean escaped = false;
    while (!isEndOfFile(lexer)) {
      boolean prevEscaped = escaped;
      escaped = false;

      char c = eatCharacter(lexer);
      if (c == '\\') escaped = true;
      if (c != '\"' || prevEscaped) continue;

      foundEndQuotes = true;
      break;
    }

    if (!foundEndQuotes) {
      recoverCursorCheckpoint(lexer);
      return null;
    }

    String value = getStringSinceCursorCheckpoint(lexer);
    String noOuterQuotes = value.substring(1, value.length() - 1);
    Token result = token_literal(noOuterQuotes, TokenLiteralType.String, consume(lexer));

    return result;
  }

  private String getStringSinceCursorCheckpoint(Lexer lexer) {
    return lexer.sourceCode.content.substring(lexer.checkpoint.at, lexer.cursor.at);
  }

  private Token makeFloatingPointLiteral(Lexer lexer, boolean hasIntegerPart, boolean hasDot, boolean hasDecimalPart, boolean hasF32Suffix) {
    // f32
    if (hasF32Suffix) {
      // "143f", "154.4f", "154.f"
      if (hasIntegerPart) {
        String value = getStringSinceCursorCheckpoint(lexer);
        Token result = token_literal(value, TokenLiteralType.F32, consume(lexer));
        return result;
      }

      // ".123f"
      if (hasDot && hasDecimalPart) {
        String value = getStringSinceCursorCheckpoint(lexer);
        Token result = token_literal(value, TokenLiteralType.F32, consume(lexer));
        return result;
      }

      return null;
    }

    // f64
    // "142.", "142.123"
    if (hasIntegerPart && hasDot) {
      String value = getStringSinceCursorCheckpoint(lexer);
      Token result = token_literal(value, TokenLiteralType.F64, consume(lexer));
      return result;
    }

    // ".123"
    if (hasDot && hasDecimalPart) {
      String value = getStringSinceCursorCheckpoint(lexer);
      Token result = token_literal(value, TokenLiteralType.F64, consume(lexer));
      return result;
    }

    return null;
  }

  private boolean eatDigits(Lexer lexer) {
    boolean found = false;

    while (!isEndOfFile(lexer)) {
      char c = peekCharacter(lexer);
      if (!isDigit(c)) break;
      eatCharacter(lexer);
      found = true;
    }

    return found;
  }

  private Token eatFloatingPointLiteral(Lexer lexer) {
    if (isEndOfFile(lexer)) return null;

    boolean integerPartExists = false;
    boolean decimalPartExists = false;
    boolean dotExists = false;
    boolean hasF32Suffix = false;

    // integer part
    char c1 = peekCharacter(lexer);
    if (isDigit(c1)) {
      integerPartExists = true;
    }

    if (isEndOfFile(lexer)) {
      clearCheckpoint(lexer);
      return null;
    }

    char c2 = peekCharacter(lexer);
    if (isDigit(c2)) {
      if (c1 == '0') {
        recoverCursorCheckpoint(lexer);
        return null;
      }
    }

    eatDigits(lexer);

    // decimal part
    char decimalPoint = peekCharacter(lexer);
    if (decimalPoint == '.') {
      eatCharacter(lexer);
      dotExists = true;
      decimalPartExists = eatDigits(lexer);
    }

    if (isEndOfFile(lexer)) {
      Token token = makeFloatingPointLiteral(lexer, integerPartExists, dotExists, decimalPartExists, hasF32Suffix);
      if (token != null) return token;

      recoverCursorCheckpoint(lexer);
      return null;
    }

    char f32Suffix = peekCharacter(lexer);
    if (f32Suffix == 'f') {
      eatCharacter(lexer);
      hasF32Suffix = true;
    }

    if (isEndOfFile(lexer)) {
      Token token = makeFloatingPointLiteral(lexer, integerPartExists, dotExists, decimalPartExists, hasF32Suffix);
      if (token != null) return token;

      recoverCursorCheckpoint(lexer);
      return null;
    }

    char cEnd = peekCharacter(lexer);
    if (!(isAlphabetic(cEnd) || cEnd == '_')) {
      Token token = makeFloatingPointLiteral(lexer, integerPartExists, dotExists, decimalPartExists, hasF32Suffix);
      if (token != null) return token;
    }

    recoverCursorCheckpoint(lexer);
    return null;
  }

  private Token eatIntegerLiteral(Lexer lexer) {
    if (isEndOfFile(lexer)) return null;

    boolean hasI64Suffix = false;

    char c1 = eatCharacter(lexer);
    if (!isDigit(c1)) {
      recoverCursorCheckpoint(lexer);
      return null;
    }

    if (isEndOfFile(lexer)) {
      recoverCursorCheckpoint(lexer);
      return null;
    }

    char c2 = peekCharacter(lexer);

    // binary
    if (c2 == 'b') {
      eatCharacter(lexer);

      if (isEndOfFile(lexer)) {
        recoverCursorCheckpoint(lexer);
        return null;
      }

      char c3 = eatCharacter(lexer);
      if (!isBinaryDigit(c3)) {
        recoverCursorCheckpoint(lexer);
        return null;
      }

      while (!isEndOfFile(lexer)) {
        char c = peekCharacter(lexer);
        if (!isBinaryDigit(c)) break;
        eatCharacter(lexer);
      }
    }

    // hex
    else if (c2 == 'x') {
      eatCharacter(lexer);

      if (isEndOfFile(lexer)) {
        recoverCursorCheckpoint(lexer);
        return null;
      }

      char c3 = eatCharacter(lexer);
      if (!isHexDigit(c3)) {
        recoverCursorCheckpoint(lexer);
        return null;
      }

      while (!isEndOfFile(lexer)) {
        char c = peekCharacter(lexer);
        if (!isHexDigit(c)) break;
        eatCharacter(lexer);
      }
    }

    // decimal more than, e.g. "12" (but not "01").
    else if (isDigit(c2)) {

      if (c1 == '0') {
        recoverCursorCheckpoint(lexer);
        return null;
      }

      eatDigits(lexer);
    }

    if (isEndOfFile(lexer)) {
      return makeIntegerLiteral(lexer, hasI64Suffix);
    }

    char i64Suffix = peekCharacter(lexer);
    if (i64Suffix == 'l') {
      eatCharacter(lexer);
      hasI64Suffix = true;
    }

    if (isEndOfFile(lexer)) {
      return makeIntegerLiteral(lexer, hasI64Suffix);
    }

    char cEnd = peekCharacter(lexer);
    if (!(isAlphabetic(cEnd) || cEnd == '.' || cEnd == '_')) {
      return makeIntegerLiteral(lexer, hasI64Suffix);
    }

    recoverCursorCheckpoint(lexer);
    return null;
  }

  private Token makeIntegerLiteral(Lexer lexer, boolean hasI64Suffix) {
    if (hasI64Suffix) {
      String value = getStringSinceCursorCheckpoint(lexer);
      Token result = token_literal(value, TokenLiteralType.I64, consume(lexer));
      return result;
    }

    String value = getStringSinceCursorCheckpoint(lexer);
    Token result = token_literal(value, TokenLiteralType.I32, consume(lexer));
    return result;
  }

  private boolean isBinaryDigit(char c) {
    return c == '0' || c == '1';
  }

  private boolean isHexDigit(char c) {
    if (c >= '0' && c <= '9') return true;
    if (c >= 'a' && c <= 'f') return true;
    if (c >= 'A' && c <= 'F') return true;
    return false;
  }

  private Token eatBooleanLiteral(Lexer lexer) {
    if (matchThenEat(lexer, "true")) return token_literal("true", TokenLiteralType.Bool, consume(lexer));
    if (matchThenEat(lexer, "false")) return token_literal("false", TokenLiteralType.Bool, consume(lexer));
    return null;
  }

  private char eatCharacter(Lexer lexer) {
    char c = peekCharacter(lexer);

    lexer.cursor.at += 1;
    lexer.cursor.characterNumber += 1;

    if (c == '\n') {
      lexer.cursor.lineNumber += 1;
      lexer.cursor.characterNumber = 1;
    }

    return c;
  }

  private Token eatCharLiteral(Lexer lexer) {
    if (isEndOfFile(lexer)) {
      return null;
    }
    char c1 = eatCharacter(lexer);
    if (c1 != '\'') {
      recoverCursorCheckpoint(lexer);
      return null;
    }

    if (isEndOfFile(lexer)) {
      recoverCursorCheckpoint(lexer);
      return null;
    }
    char c2 = eatCharacter(lexer);
    if (c2 == '\\') eatCharacter(lexer); // escape character

    if (isEndOfFile(lexer)) {
      recoverCursorCheckpoint(lexer);
      return null;
    }
    char c3 = eatCharacter(lexer);
    if (c3 != '\'') {
      recoverCursorCheckpoint(lexer);
      return null;
    }

    String value = getStringSinceCursorCheckpoint(lexer);
    Token result = token_literal(value, TokenLiteralType.Char, consume(lexer));

    return result;
  }

  private boolean matchThenEat(Lexer lexer, String string) {
    boolean match = matches(lexer, string);
    if (!match) return false;
    eatMultipleCharacters(lexer, string.length());
    return true;
  }

  private boolean eatWord(Lexer lexer, String string) {
    boolean success = matches(lexer, string);
    if (!success) return false;

    eatMultipleCharacters(lexer, string.length());
    if (isEndOfFile(lexer)) return true;

    char c = peekCharacter(lexer);
    recoverCursorCheckpoint(lexer);

    // ok: ( ) { } whitespace ; : 
    // bad: [a-zA-Z] [0-9] _
    if (isDigit(c)) return false;
    if (isAlphabetic(c)) return false;
    if (c == '_') return false;

    eatMultipleCharacters(lexer, string.length());

    return true;
  }

  private Token eatIdentifier(Lexer lexer) {
    if (isEndOfFile(lexer)) return null;

    char c1 = peekCharacter(lexer);
    if (!(isAlphabetic(c1) || c1 == '_')) return null;
    eatCharacter(lexer);

    while (!isEndOfFile(lexer)) {
      char c = peekCharacter(lexer);
      if (!(isDigit(c) || isAlphabetic(c) || c == '_')) break;
      eatCharacter(lexer);
    }

    String value = getStringSinceCursorCheckpoint(lexer);
    Token result = token_identifier(value, consume(lexer));

    return result;
  }

  private void recoverCursorCheckpoint(Lexer lexer) {
    lexer.cursor.at = lexer.checkpoint.at;
    lexer.cursor.characterNumber = lexer.checkpoint.characterNumber;
    lexer.cursor.lineNumber = lexer.checkpoint.lineNumber;
  }

  private void clearCheckpoint(Lexer lexer) {
    lexer.checkpoint.at = lexer.cursor.at;
    lexer.checkpoint.characterNumber = lexer.cursor.characterNumber;
    lexer.checkpoint.lineNumber = lexer.cursor.lineNumber;
  }

  default boolean isDigit(char c) {
    return Character.isDigit(c);
  }

  default boolean isAlphabetic(char c) {
    return Character.isAlphabetic(c);
  }

  private boolean eatSymbols(Lexer lexer, String string) {
    return matchThenEat(lexer, string);
  }

  private int getRemainingCharactersCount(Lexer lexer) {
    return lexer.sourceCode.content.length() - lexer.cursor.at;
  }

  private boolean matches(Lexer lexer, String string) {
    int remaining = getRemainingCharactersCount(lexer);

    for (int i = 0; i < string.length(); i++) {
      if (i >= remaining) return false;

      char programChar = peekAhead(lexer, i);
      char stringChar = string.charAt(i);

      if (stringChar != programChar) return false;
    }
    return true;
  }

  private void eatMultipleCharacters(Lexer lexer, int eatCount) {
    for (int i = 0; i < eatCount; i++) {
      eatCharacter(lexer);
    }
  }

  private char peekCharacter(Lexer lexer) {
    return peekAhead(lexer, 0);
  }

  private char peekAhead(Lexer lexer, int offset) {
    return lexer.sourceCode.content.charAt(lexer.cursor.at + offset);
  }

  private void eatWhitespaces(Lexer lexer) {
    while (!isEndOfFile(lexer)) {
      char c = peekCharacter(lexer);
      if (!isWhitespace(c)) break;
      eatCharacter(lexer);
    }

    clearCheckpoint(lexer);
  }

  private boolean isWhitespace(char c) {
    return c == ' ' || c == '\r' || c == '\n' || c == '\t';
  }

  private boolean isEndOfFile(Lexer lexer) {
    return getRemainingCharactersCount(lexer) <= 0;
  }

}
