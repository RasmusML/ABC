package pack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Stack;

public interface TypeCheckerModule extends ParserModule {

  static public class TypeChecker {
    public Map<String, AstStruct> nameToStruct;
    public Map<String, List<AstFunction>> nameToFunctions;
    public Stack<Scope> scopes;
    public AstProgram astProgram;

    public SourceFile sourceCode;
  }

  static public class Scope {
    public boolean hasReturnStatement;
    public Map<String, AstDeclaration> variables;
  }

  static public class FunctionScope extends Scope {
    public AstFunction function;
    public boolean hasVarargsParameter;
    public Map<String, AstParameterDeclaration> parameters;  // for fast lookup. "function" also contains the parameters
  }
  
  default void typeCheck(AstProgram astProgram) {
    AstCompilationUnit mainUnit = astProgram.compilationUnits.get(0);

    TypeChecker typeChecker = new TypeChecker();
    typeChecker.astProgram = astProgram;
    typeChecker.scopes = new Stack<>();
    typeChecker.nameToStruct = new HashMap<>();
    typeChecker.nameToFunctions = new HashMap<>();
    typeChecker.sourceCode = mainUnit.sourceFile;

    for (AstStruct struct : mainUnit.structs) {
      AstStruct collision = findStruct(typeChecker, struct.name);
      if (collision != null) reportError(typeChecker, struct, "redeclaration of struct \"%s\".", struct.name);
      typeChecker.nameToStruct.put(struct.name, struct);
    }

    for (AstFunction function : mainUnit.functions) {
      declareFunction(typeChecker, function);

      if (function.name.equals("main") && function.parameters.size() == 0) {
        mainUnit.hasProgramEntry = true;
      }
    }

    for (AstStruct struct : mainUnit.structs) {

      if (struct.hasJavaLibraryBinding) {
        if (!isJavaLibraryNameValid(struct.javaLibraryName)) {
          reportError(typeChecker, struct, "invalid java library name. \"%s\".", struct.javaLibraryName);
        }
        mainUnit.javaLibraryDependencyNames.add(struct.javaLibraryName);

      } else {
        for (AstStructField field : struct.fields) {
          if (field.type.category != AstTypeCategory.Struct) continue;

          AstStruct structMember = findStruct(typeChecker, field.type.structName);
          if (structMember == null) {
            reportError(typeChecker, field, "undefined struct type \"%s\" for struct member \"%s\" in struct \"%s\".", field.type.structName, field.name, struct.name);
          }
        }
      }
    }

    for (AstFunction function : mainUnit.functions) {

      if (function.returnType.category == AstTypeCategory.Struct) {
        AstStruct structMember = findStruct(typeChecker, function.returnType.structName);
        if (structMember == null) reportError(typeChecker, function, "undefined struct type \"%s\" for function \"%s\"'s return-type.", function.returnType.structName, function.name);
      }

      FunctionScope functionScope = createFunctionScope(function);
      pushScope(typeChecker, functionScope);

      for (AstParameterDeclaration parameter : function.parameters) {
        declareParameterInScope(typeChecker, parameter);
      }

      if (function.hasJavaLibraryBinding) {
        if (!isJavaLibraryNameValid(function.javaLibraryName)) {
          reportError(typeChecker, function, "invalid java library name. \"%s\".", function.javaLibraryName);
        }
        mainUnit.javaLibraryDependencyNames.add(function.javaLibraryName);

      } else {
        typeCheckStatements(typeChecker, function.bodyStatements);

        if (functionScope.function.returnType.category != AstTypeCategory.Void) {
          if (!functionScope.hasReturnStatement) reportError(typeChecker, function, "function does not have exhaustive \"return\" statements.");
        }
      }

      popScope(typeChecker);
    }
  }

  private boolean isJavaLibraryNameValid(String javaLibraryName) {
    if (javaLibraryName.length() == 0) return false;

    char c1 = javaLibraryName.charAt(0);
    if (!(isAlphabetic(c1) || c1 == '_')) return false;

    for (int i = 1; i < javaLibraryName.length(); i++) {
      char c = javaLibraryName.charAt(i);
      if (!(isDigit(c) || isAlphabetic(c) || c == '_')) return false;
    }

    return true;
  }

  private AstStruct findStruct(TypeChecker typeChecker, String structName) {
    return typeChecker.nameToStruct.get(structName);
  }

  private void declareFunction(TypeChecker typeChecker, AstFunction function) {
    if (!typeChecker.nameToFunctions.containsKey(function.name)) {
      List<AstFunction> functionOverloads = new ArrayList<>();
      typeChecker.nameToFunctions.put(function.name, functionOverloads);
    }

    List<AstFunction> functionOverloads = typeChecker.nameToFunctions.get(function.name);
    for (AstFunction collision : functionOverloads) {
      if (!areFunctionSignaturesIdentical(function, collision)) continue;
      reportError(typeChecker, function, "redeclaration of function \"%s\".", function.name);
    }

    functionOverloads.add(function);
  }

  private boolean areArgumentsFittingIntoParameters(List<AstParameterDeclaration> parameters, List<AstType> argumentTypes) {
    if (parameters.size() > 0) {
      // varargs
      AstParameterDeclaration lastParameter = parameters.get(parameters.size() - 1);
      if (lastParameter.type.isVarargs) {
        for (int i = 0; i < parameters.size() - 1; i++) {
          AstParameterDeclaration parameter = parameters.get(i);
          AstType argumentType = argumentTypes.get(i);
          if (!doesTypeFit(argumentType, parameter.type)) return false;
        }

        for (int i = parameters.size() - 1; i < argumentTypes.size(); i++) {
          AstType argumentType = argumentTypes.get(i);
          if (!doesTypeFit(argumentType, lastParameter.type)) return false;
        }

        return true;
      }
    }

    // no varargs
    if (parameters.size() != argumentTypes.size()) return false;

    for (int i = 0; i < parameters.size(); i++) {
      AstParameterDeclaration parameter = parameters.get(i);
      AstType argumentType = argumentTypes.get(i);
      if (!doesTypeFit(argumentType, parameter.type)) return false;
    }

    return true;
  }

  private boolean areArgumentsMatchingParameters(List<AstParameterDeclaration> parameters, List<AstType> argumentTypes) {
    if (parameters.size() != argumentTypes.size()) return false;

    for (int i = 0; i < parameters.size(); i++) {
      AstParameterDeclaration parameter = parameters.get(i);
      AstType argumentType = argumentTypes.get(i);
      if (!areExactSameType(parameter.type, argumentType)) return false;
    }

    return true;
  }

  private boolean areFunctionSignaturesIdentical(AstFunction function1, AstFunction function2) {
    if (!function1.name.equals(function2.name)) return false;
    if (function1.parameters.size() != function2.parameters.size()) return false;

    for (int i = 0; i < function1.parameters.size(); i++) {
      AstParameterDeclaration p1 = function1.parameters.get(i);
      AstParameterDeclaration p2 = function2.parameters.get(i);
      if (!areExactSameType(p1.type, p2.type)) return false;
    }

    return true;
  }

  private boolean areExactSameType(AstType t1, AstType t2) {
    if (t1.arrayDimension != t2.arrayDimension) return false;
    if (t1.isVarargs != t2.isVarargs) return false;
    if (t1.category == AstTypeCategory.Struct && t2.category == AstTypeCategory.Struct) {
      return t1.structName.equals(t2.structName);
    }
    return t1.category == t2.category;
  }

  private FunctionScope createFunctionScope(AstFunction function) {
    FunctionScope result = new FunctionScope();
    result.function = function;
    result.variables = new HashMap<>();
    result.parameters = new HashMap<>();
    return result;
  }

  private void declareParameterInScope(TypeChecker typeChecker, AstParameterDeclaration parameter) {
    FunctionScope functionScope = getFunctionScope(typeChecker);
    if (functionScope.hasVarargsParameter) reportError(typeChecker, parameter, "no parameter can follow after a varargs parameter.");

    if (parameter.type.category == AstTypeCategory.Void) reportError(typeChecker, parameter, "parameter \"%s\" can't be of type \"void\".", parameter.name);

    if (parameter.type.category == AstTypeCategory.Struct) {
      AstStruct struct = findStruct(typeChecker, parameter.type.structName);
      if (struct == null) reportError(typeChecker, parameter, "undefined struct-type \"%s\" for parameter \"%s\".", parameter.type.structName, parameter.name);
    }

    AstParameterDeclaration collision = findParameterInScope(typeChecker, parameter.name);
    if (collision != null) reportError(typeChecker, parameter, "redeclaration of parameter %s: %s.", collision.name, collision.type.category.name());

    functionScope.hasVarargsParameter |= parameter.type.isVarargs;
    functionScope.parameters.put(parameter.name, parameter);
  }

  private void declareLocalVariableInScope(TypeChecker typeChecker, AstDeclaration declaration) {
    if (declaration.type.category == AstTypeCategory.Void) reportError(typeChecker, declaration, "variable \"%s\" can't be of type \"void\".", declaration.identifier);

    if (declaration.type.category == AstTypeCategory.Struct) {
      AstStruct struct = findStruct(typeChecker, declaration.type.structName);
      if (struct == null) reportError(typeChecker, declaration, "undefined struct type \"%s\" for variable \"%s\".", declaration.type.structName, declaration.identifier);
    }

    AstParameterDeclaration parameterCollision = findParameterInScope(typeChecker, declaration.identifier);
    if (parameterCollision != null) reportError(typeChecker, declaration, "redeclaration of %s: %s.", parameterCollision.name, parameterCollision.type.category.name());

    AstDeclaration variableCollision = findVariableInScope(typeChecker, declaration.identifier);
    if (variableCollision != null) reportError(typeChecker, declaration, "redeclaration of %s: %s.", variableCollision.identifier, variableCollision.type.category.name());

    Scope current = getTopScope(typeChecker);
    current.variables.put(declaration.identifier, declaration);
  }

  private Scope getTopScope(TypeChecker typeChecker) {
    return typeChecker.scopes.lastElement();
  }

  private Scope getBottomScope(TypeChecker typeChecker) {
    return typeChecker.scopes.firstElement();
  }

  private FunctionScope getFunctionScope(TypeChecker typeChecker) {
    Scope scope = getBottomScope(typeChecker);

    if (scope instanceof FunctionScope) {
      return (FunctionScope) scope;
    }

    throw new CompilerException("broken, bottom score is not a function scope!");
  }

  private AstDeclaration findVariableInScope(TypeChecker typeChecker, String name) {
    for (Scope scope : typeChecker.scopes) {
      AstDeclaration match = scope.variables.get(name);
      if (match != null) return match;
    }
    return null;
  }

  private AstParameterDeclaration findParameterInScope(TypeChecker typeChecker, String name) {
    for (Scope scope : typeChecker.scopes) {
      if (scope instanceof FunctionScope) {
        FunctionScope functionScope = (FunctionScope) scope;
        AstParameterDeclaration match = functionScope.parameters.get(name);
        if (match != null) return match;
      }
    }
    return null;
  }

  private AstLiteral getDefaultValueForType(AstType type) {
    boolean varargs = false;
    int arrayDimension = 0;
    Location location = null;

    if (type.arrayDimension > 0) return astLiteral("nil", astType_primitive(AstTypeCategory.Object, varargs, arrayDimension), location);
    if (type.category == AstTypeCategory.I8) return astLiteral("0", astType_primitive(AstTypeCategory.I32, varargs, arrayDimension), location);
    if (type.category == AstTypeCategory.I16) return astLiteral("0", astType_primitive(AstTypeCategory.I32, varargs, arrayDimension), location);
    if (type.category == AstTypeCategory.I32) return astLiteral("0", astType_primitive(AstTypeCategory.I32, varargs, arrayDimension), location);
    if (type.category == AstTypeCategory.I64) return astLiteral("0", astType_primitive(AstTypeCategory.I64, varargs, arrayDimension), location);
    if (type.category == AstTypeCategory.F32) return astLiteral("0", astType_primitive(AstTypeCategory.F32, varargs, arrayDimension), location);
    if (type.category == AstTypeCategory.F64) return astLiteral("0", astType_primitive(AstTypeCategory.F64, varargs, arrayDimension), location);
    if (type.category == AstTypeCategory.Char) return astLiteral("0", astType_primitive(AstTypeCategory.I32, varargs, arrayDimension), location);
    if (type.category == AstTypeCategory.Bool) return astLiteral("false", astType_primitive(AstTypeCategory.Bool, varargs, arrayDimension), location);
    if (type.category == AstTypeCategory.Struct) return astLiteral("nil", astType_primitive(AstTypeCategory.Object, varargs, arrayDimension), location);
    if (type.category == AstTypeCategory.String) return astLiteral("nil", astType_primitive(AstTypeCategory.Object, varargs, arrayDimension), location);
    if (type.category == AstTypeCategory.Any) return astLiteral("nil", astType_primitive(AstTypeCategory.Object, varargs, arrayDimension), location);
    throw new CompilerException("type %s does not have a default type!", typeToString(type));
  }

  private <T> boolean isAny(T value, T first, T... rest) {
    if (value.equals(first)) return true;

    for (T other : rest) {
      if (value.equals(other)) return true;
    }
    return false;
  }

  private AstStructField findFieldInStruct(AstStruct struct, String fieldName) {
    for (AstStructField field : struct.fields) {
      if (field.name.equals(fieldName)) return field;
    }
    return null;
  }

  private AstType inferFunctionCallType(TypeChecker typeChecker, AstFunctionCall functionCall) {
    List<AstFunction> functions = typeChecker.nameToFunctions.get(functionCall.name);
    if (functions == null) {
      reportError(typeChecker, functionCall, "trying to call an undeclared function: \"%s\".", functionCall.name);
    }

    List<AstType> argumentTypes = new ArrayList<>();
    for (AstExpression argument : functionCall.arguments) {
      AstType argumentType = inferExpressionType(typeChecker, argument);
      argumentTypes.add(argumentType);
    }

    AstFunction function = null;
    boolean autoCastMatch = false;
    for (AstFunction candidate : functions) {

      boolean identical = areArgumentsMatchingParameters(candidate.parameters, argumentTypes);
      if (identical) {
        function = candidate;
        break;
      }

      boolean fitting = areArgumentsFittingIntoParameters(candidate.parameters, argumentTypes);
      if (fitting) {
        if (autoCastMatch) reportError(typeChecker, functionCall, "multiple function are matching argument signature.");
        autoCastMatch = true;
        function = candidate;
      }
    }

    if (function == null) {
      if (functions.size() != 0) {
        reportError(typeChecker, functionCall, "function \"%s\" parameter types do not match argument types.", functionCall.name);
      }
      reportError(typeChecker, functionCall, "trying to call an undeclared function: \"%s\".", functionCall.name);
    }

    return function.returnType;
  }

  private AstType autoPromoteType(AstType lhsType) {
    if (lhsType.category == AstTypeCategory.Char) return astType_primitive(AstTypeCategory.I32, false, 0);
    return getBiggestNumberTypeBetween(lhsType, astType_primitive(AstTypeCategory.I32, false, 0));
  }

  private AstType autoPromoteTypeAfterBinaryOperation(AstType lhsType, AstType rhsType) {
    AstType t1 = autoPromoteType(lhsType);
    AstType t2 = autoPromoteType(rhsType);
    return getBiggestNumberTypeBetween(t1, t2);
  }

  private AstVariable inferVariable(TypeChecker typeChecker, AstVariable variable) {
    assertIt(variable.type == null);

    AstType baseType = null;

    AstDeclaration declaredVariable = findVariableInScope(typeChecker, variable.name);
    if (declaredVariable != null) baseType = declaredVariable.type;

    AstParameterDeclaration declaredParameter = findParameterInScope(typeChecker, variable.name);
    if (declaredParameter != null) baseType = declaredParameter.type;

    if (declaredVariable == null && declaredParameter == null) {
      reportError(typeChecker, variable, "undeclared variable.");
    }

    AstType type = inferArrayVariableUnpackedType(typeChecker, variable, baseType);
    variable.type = type;

    if (variable.child != null) return inferVariableSubField(typeChecker, variable.child, variable);

    return variable;
  }

  private AstVariable inferVariableSubField(TypeChecker typeChecker, AstVariable variable, AstVariable parent) {
    assertIt(parent.type != null);
    assertIt(variable.type == null);

    if (parent.type.arrayDimension > 0 || parent.type.isVarargs) {

      // built-in field
      if (variable.name.equals("length")) {
        variable.readOnly = true;
        variable.type = astType_primitive(AstTypeCategory.I32, false, 0);
        if (variable.child != null) reportError(typeChecker, variable, "built in array field \"%s\" doesn't have any subfields.", variable.name);
        return variable;
      }

      reportError(typeChecker, variable, "arrays don't contain field \"%s\".", variable.name);
    }

    if (parent.type.category == AstTypeCategory.Struct) {
      AstStruct struct = findStruct(typeChecker, parent.type.structName);
      assertIt(struct != null);

      AstStructField field = findFieldInStruct(struct, variable.name);
      if (field == null) reportError(typeChecker, variable, "struct %s doesn't contain field \"%s\".", struct.name, variable.name);

      AstType type = inferArrayVariableUnpackedType(typeChecker, variable, field.type);
      variable.type = type;

      if (variable.child != null) return inferVariableSubField(typeChecker, variable.child, variable);
      return variable;
    }

    reportError(typeChecker, variable, "type %s doesn't have any subfields.", typeToString(parent.type));
    return null;
  }

  private AstType inferArrayVariableUnpackedType(TypeChecker typeChecker, AstVariable arrayVariable, AstType arrayType) {
    assertIt(arrayVariable.type == null);

    AstType unpackedType = astType(arrayType.category, arrayType.structName, arrayType.isVarargs, arrayType.arrayDimension);

    // no unpacking
    if (arrayVariable.arrayExpressions.size() == 0) {
      return unpackedType;
    }

    if (unpackedType.isVarargs) {
      unpackedType.isVarargs = false;
      unpackedType.arrayDimension += 1;
    }

    unpackedType.arrayDimension -= arrayVariable.arrayExpressions.size();
    if (unpackedType.arrayDimension < 0) reportError(typeChecker, arrayVariable, "too many array unpacking. Max: %d, Got: %d.", arrayType.arrayDimension, arrayVariable.arrayExpressions.size());

    for (AstExpression arraySubExpression : arrayVariable.arrayExpressions) {
      AstType indexType = inferExpressionType(typeChecker, arraySubExpression);
      if (!isNumberOrCharType(indexType)) reportError(typeChecker, arraySubExpression, "expected numeric type when array indexing (%s), but got %s.", arrayVariable.name, typeToString(indexType));
    }

    return unpackedType;
  }

  private AstType inferExpressionType(TypeChecker typeChecker, AstExpression expression) {

    if (expression instanceof AstFunctionCall) {
      AstFunctionCall functionCall = (AstFunctionCall) expression;
      AstType returnType = inferFunctionCallType(typeChecker, functionCall);
      return returnType;
    }

    if (expression instanceof AstVariable) {
      AstVariable variable = (AstVariable) expression;
      AstVariable child = inferVariable(typeChecker, variable);
      return child.type;
    }

    if (expression instanceof AstTypeCast) {
      AstTypeCast typecast = (AstTypeCast) expression;
      AstType expressionType = inferExpressionType(typeChecker, typecast.expression);

      if (!areTypesCompatible(typecast.type, expressionType)) {
        reportError(typeChecker, typecast.expression, "failed to cast %s to %s.", typeToString(expressionType), typeToString(typecast.type));
      }

      return typecast.type;
    }

    if (expression instanceof AstLiteral) {
      AstLiteral literal = (AstLiteral) expression;

      if (literal.type.category == AstTypeCategory.Char) {
        ParsedChar parsedChar = parseChar(literal.value);
        if (!parsedChar.success) reportError(typeChecker, literal, "invalid character \"%s\".", literal.value);

        literal.integerValue = parsedChar.value;
        return literal.type;
      }

      // skipped i8, i16, because they are not defined as literals.

      if (literal.type.category == AstTypeCategory.I32) {
        IntegerBase integerBase = extractIntegerBase(literal.value);
        ParsedI32 parsedI32 = parseI32(integerBase.number, integerBase.base);
        if (!parsedI32.success) reportError(typeChecker, literal, "i32 literal %s is outside range [%d, %d].", literal.value, Integer.MIN_VALUE, Integer.MAX_VALUE);

        literal.integerValue = parsedI32.value;
        return literal.type;
      }

      if (literal.type.category == AstTypeCategory.I64) {
        ParsedI64 parsedI64 = parseI64(literal.value);
        if (!parsedI64.success) reportError(typeChecker, literal, "i64 literal %s is outside range [%d, %d].", literal.value, Long.MIN_VALUE, Long.MAX_VALUE);

        literal.integerValue = parsedI64.value;
        return literal.type;
      }

      if (literal.type.category == AstTypeCategory.F32) {
        ParsedF32 parsedF32 = parseF32(literal.value);
        if (!parsedF32.success) reportError(typeChecker, literal, "f32 literal %s is outide range [%f, %f].", literal.value, Float.MIN_VALUE, Float.MAX_VALUE);

        literal.floatingPointValue = parsedF32.value;
        return literal.type;
      }

      if (literal.type.category == AstTypeCategory.F64) {
        ParsedF64 parsedF64 = parseF64(literal.value);
        if (!parsedF64.success) reportError(typeChecker, literal, "f64 literal %s is outide range [%f, %f].", literal.value, Double.MIN_VALUE, Double.MAX_VALUE);

        literal.floatingPointValue = parsedF64.value;
        return literal.type;
      }

      return literal.type;
    }

    if (expression instanceof AstBinaryOperator) {
      AstBinaryOperator binaryOperator = (AstBinaryOperator) expression;

      AstType lhsType = inferExpressionType(typeChecker, binaryOperator.lhs);
      AstType rhsType = inferExpressionType(typeChecker, binaryOperator.rhs);

      if (isNumberOrCharType(lhsType) && isNumberOrCharType(rhsType)) {

        if (isIntegerType(lhsType) && isIntegerType(rhsType)) {
          if (isAny(binaryOperator.operator, "|", "&", "^", "<<", ">>", ">>>")) {
            return autoPromoteTypeAfterBinaryOperation(lhsType, rhsType);
          }
        }

        if (isAny(binaryOperator.operator, "+", "-", "/", "*", "%")) {
          return autoPromoteTypeAfterBinaryOperation(lhsType, rhsType);
        }

        if (isAny(binaryOperator.operator, ">", "<", ">=", "<=", "==", "!=")) {
          return astType_primitive(AstTypeCategory.Bool, false, 0);
        }

        String operator = binaryOperator.operator.equals("%") ? "%%" : binaryOperator.operator;
        reportError(typeChecker, binaryOperator, "invalid binary operator \" %s \" for the types: %s %s.", operator, lhsType.category, rhsType.category);
      }

      if (lhsType.category == AstTypeCategory.Bool && rhsType.category == AstTypeCategory.Bool) {
        if (isAny(binaryOperator.operator, "&&", "||", "==", "!=", "^")) return astType_primitive(AstTypeCategory.Bool, false, 0);
        reportError(typeChecker, binaryOperator, "invalid binary operator \"%s\" for the types: %s %s.", binaryOperator.operator, lhsType.category, rhsType.category);
      }

      if (lhsType.category == AstTypeCategory.Struct || lhsType.category == AstTypeCategory.String || lhsType.arrayDimension > 0) {
        if (doesTypeFit(rhsType, lhsType)) {
          if (isAny(binaryOperator.operator, "==", "!=")) return astType_primitive(AstTypeCategory.Bool, false, 0);
        }
      }

      reportError(typeChecker, binaryOperator, "invalid types. failed to apply binary operator: %s %s %s.", lhsType.category, binaryOperator.operator, rhsType.category);
    }

    if (expression instanceof AstUnaryOperator) {
      AstUnaryOperator unaryOperator = (AstUnaryOperator) expression;
      AstType bodyType = inferExpressionType(typeChecker, unaryOperator.body);

      if (isNumberOrCharType(bodyType)) {
        if (!isAny(unaryOperator.operator, "+", "-", "~")) {
          reportError(typeChecker, unaryOperator, "invalid unary operator \"%s\" on type: %s.", unaryOperator.operator, typeToString(bodyType));
        }
        return bodyType;
      }

      if (bodyType.category == AstTypeCategory.Bool) {
        if (!isAny(unaryOperator.operator, "!")) {
          reportError(typeChecker, unaryOperator, "invalid unary operator \"%s\" on type: %s.", unaryOperator.operator, typeToString(bodyType));
        }
        return bodyType;
      }

      reportError(typeChecker, unaryOperator, "invalid type. failed to apply unary \"%s\" operator on type: %s.", unaryOperator.operator, typeToString(bodyType));
    }

    if (expression instanceof AstParenthesis) {
      AstParenthesis parenthesis = (AstParenthesis) expression;
      AstType type = inferExpressionType(typeChecker, parenthesis.body);
      return type;
    }

    throw new CompilerException("failed to infer type for expression: %s", expression.getClass().getSimpleName());
  }

  static public class ParsedI32 {
    public boolean success;
    public int value;
  }

  static public class IntegerBase {
    public String number;
    public int base;
  }

  private IntegerBase extractIntegerBase(String integerWithBasePrefix) {
    IntegerBase result = new IntegerBase();
    result.number = integerWithBasePrefix;
    result.base = 10;

    if (integerWithBasePrefix.length() < 2) return result;

    String baseSpecifier = integerWithBasePrefix.substring(0, 2);
    if ("0b".equals(baseSpecifier)) result.base = 2;
    else if ("0x".equals(baseSpecifier)) result.base = 16;

    if (result.base != 10) result.number = integerWithBasePrefix.substring(2);

    return result;
  }

  private ParsedI32 parseI32(String value, int base) {
    ParsedI32 result = new ParsedI32();

    try {
      result.success = true;
      result.value = Integer.parseInt(value, base);
    } catch (NumberFormatException e) {
      result.success = false;
    }

    return result;
  }

  static public class ParsedI64 {
    public boolean success;
    public long value;
  }

  private ParsedI64 parseI64(String value) {
    ParsedI64 result = new ParsedI64();

    // @TODO: cleanup should the astliteral just remove suffixes, "l", "f" (same for chars and strings)
    // remove suffix "l"
    if (value.length() >= 1) {
      char last = value.charAt(value.length() - 1);
      if (last == 'l') value = value.substring(0, value.length() - 1);
    }

    try {
      result.success = true;
      result.value = Long.parseLong(value);
    } catch (NumberFormatException e) {
      result.success = false;
    }

    return result;
  }

  static public class ParsedF32 {
    public boolean success;
    public float value;
  }

  private ParsedF32 parseF32(String value) {
    ParsedF32 result = new ParsedF32();

    try {
      result.success = true;
      result.value = Float.parseFloat(value);
    } catch (NumberFormatException e) {
      result.success = false;
    }

    return result;
  }

  static public class ParsedF64 {
    public boolean success;
    public double value;
  }

  private ParsedF64 parseF64(String value) {
    ParsedF64 result = new ParsedF64();

    try {
      result.success = true;
      result.value = Double.parseDouble(value);
    } catch (NumberFormatException e) {
      result.success = false;
    }

    return result;
  }

  static public class ParsedChar {
    public boolean success;
    public char value;
  }

  private ParsedChar parseChar(String charString) {
    ParsedChar result = new ParsedChar();

    if (charString.length() < 2) return result;

    // remove start and end single quotes '.
    charString = charString.substring(1, charString.length() - 1);
    int charLength = charString.length();

    if (charLength == 1) {
      result.value = charString.charAt(0);
      result.success = true;
      return result;
    }

    if (charLength == 2) {
      if (!charString.startsWith("\\")) return result;

      String[] escapeSequencesRaw = { "\\t", "\\b", "\\n", "\\r", "\\f", "\\'", "\\\"", "\\\\" };
      char[] escapeSequences = { '\t', '\b', '\n', '\r', '\f', '\'', '\"', '\\' };

      for (int i = 0; i < escapeSequences.length; i++) {
        String raw = escapeSequencesRaw[i];
        if (!raw.equals(charString)) continue;

        result.success = true;
        result.value = escapeSequences[i];

        return result;
      }
    }

    return result;
  }

  private boolean doesTypeFit(AstType from, AstType to) {
    if (to.isVarargs && from.isVarargs) return doesVarArgsFit_fromTo(from, to);
    if (to.isVarargs && !from.isVarargs) return doesVarArgsFit_to(from, to);
    if (!to.isVarargs && from.isVarargs) return doesVarArgsFit_from(from, to);
    if (!to.isVarargs && !from.isVarargs) return doesNonVarArgsFit(from, to);
    throw new CompilerException("non-exhaustive branches");
  }

  default boolean doesVarArgsFit_fromTo(AstType from, AstType to) {
    // ..all ::= ..all
    if (to.arrayDimension == 0 && from.arrayDimension == 0) {
      // ..any ::= ..all
      if (to.category == AstTypeCategory.Any) return true;

      // ..object ::= ..object | ..struct | ..string
      if (from.category == AstTypeCategory.Object) {
        if (to.category == AstTypeCategory.Struct) return true;
        if (to.category == AstTypeCategory.String) return true;
        return false;
      }

      // ..type ::= ..type
      return doSimpleTypesMatch(from, to);
    }

    // ..all[]? ::= ..all
    if (to.arrayDimension >= 0 && from.arrayDimension == 0) {
      if (from.category == AstTypeCategory.Object) return true;
      return false;
    }

    // ..all ::= ..all[]?
    if (to.arrayDimension == 0 && from.arrayDimension >= 0) {
      if (to.category == AstTypeCategory.Any) return true;
      return false;
    }

    // ..all[]? ::= ..all[]?
    if (to.arrayDimension >= 0 && from.arrayDimension >= 0) {
      // ..all[][][] ::= ..all[][]
      if (to.arrayDimension != from.arrayDimension) return false;

      // ..all[][][] ::= ..all[][][]
      return doSimpleTypesMatch(from, to);
    }

    throw new CompilerException("non-exhaustive branches");
  }

  private boolean doesVarArgsFit_to(AstType from, AstType to) {
    return doesNonVarArgsFit(from, to);
  }

  private boolean doesNonVarArgsFit(AstType from, AstType to) {
    // all ::= all
    if (to.arrayDimension == 0 && from.arrayDimension == 0) {
      // any ::= all
      if (to.category == AstTypeCategory.Any) return true;

      // object ::= object | struct | string
      if (from.category == AstTypeCategory.Object) {
        if (to.category == AstTypeCategory.Struct) return true;
        if (to.category == AstTypeCategory.String) return true;
        return false;
      }

      // type ::= type
      return doesSimpleTypeFit(from, to);
    }

    // all[]? ::= all
    if (to.arrayDimension >= 0 && from.arrayDimension == 0) {
      if (from.category == AstTypeCategory.Object) return true;
    }

    // all ::= all[]?
    if (to.arrayDimension == 0 && from.arrayDimension >= 0) {
      if (to.category == AstTypeCategory.Any) return true;
      return false;
    }

    // all[]? ::= all[]?
    if (to.arrayDimension >= 0 && from.arrayDimension >= 0) {
      // all[][][] ::= all[][]
      if (to.arrayDimension != from.arrayDimension) return false;

      // all[][][] ::= all[][][]
      return doSimpleTypesMatch(from, to);
    }

    throw new CompilerException("non-exhaustive branches");
  }

  default boolean doesVarArgsFit_from(AstType from, AstType to) {
    // all ::= ..all
    if (to.arrayDimension == 0 && from.arrayDimension == 0) {
      return false;
    }

    // all[]? ::= ..all
    if (to.arrayDimension >= 0 && from.arrayDimension == 0) {
      if (to.arrayDimension != 1) return false;

      // all[] ::= ..all
      return doesSimpleTypeFit(from, to);
    }

    // all ::= ..all[]?
    if (to.arrayDimension == 0 && from.arrayDimension >= 0) {
      return false;
    }

    // all[]? ::= ..all[]?
    if (to.arrayDimension >= 0 && from.arrayDimension >= 0) {
      if (to.arrayDimension != from.arrayDimension + 1) return false;

      // all[][][][] ::= ..all[][][]
      return doSimpleTypesMatch(from, to);
    }

    throw new CompilerException("non-exhaustive branches");
  }

  default boolean doesSimpleTypeFit(AstType from, AstType to) {
    if (doSimpleTypesMatch(from, to)) return true;
    return doesPrimitiveTypeFit(from, to);
  }

  default boolean doSimpleTypesMatch(AstType from, AstType to) {
    if (to.category == AstTypeCategory.Any) return true;

    if (to.category == from.category) {
      if (to.category == AstTypeCategory.Struct) {
        return to.structName.equals(from.structName);
      }
      return true;
    }
    return false;
  }

  private boolean doesPrimitiveTypeFit(AstType from, AstType to) {
    if (from.category == AstTypeCategory.Char && to.category == AstTypeCategory.Char) return true;
    if (from.category == AstTypeCategory.Bool && to.category == AstTypeCategory.Bool) return true;

    if (isNumberType(to) && isNumberType(from)) {
      int t2Value = getNumberTypeHierarchicValue(to.category);
      int t1Value = getNumberTypeHierarchicValue(from.category);
      return t2Value >= t1Value;
    }

    if (isNumberType(to) && from.category == AstTypeCategory.Char) {
      int minimumSize = getNumberTypeHierarchicValue(AstTypeCategory.I32);
      return getNumberTypeHierarchicValue(to.category) >= minimumSize;
    }

    return false;
  }

  private boolean isNumberType(AstType type) {
    return type.category == AstTypeCategory.I8 || type.category == AstTypeCategory.I16 || type.category == AstTypeCategory.I32 || type.category == AstTypeCategory.I64 || type.category == AstTypeCategory.F32 || type.category == AstTypeCategory.F64;
  }

  private boolean isNumberOrCharType(AstType type) {
    return isNumberType(type) || type.category == AstTypeCategory.Char;
  }

  private boolean areTypesCompatible(AstType t1, AstType t2) {
    return doesTypeFit(t1, t2) || doesTypeFit(t2, t1);
  }

  private AstType getBiggestNumberTypeBetween(AstType t1, AstType t2) {
    int t1Value = getNumberTypeHierarchicValue(t1.category);
    int t2Value = getNumberTypeHierarchicValue(t2.category);
    if (t1Value > t2Value) return astType_primitive(t1.category, false, 0);
    return astType_primitive(t2.category, false, 0);
  }

  private String typeToString(AstType type) {
    StringBuilder builder = new StringBuilder();

    if (type.isVarargs) {
      builder.append("[]");
    }

    for (int i = 0; i < type.arrayDimension; i++) {
      builder.append("[]");
    }

    if (type.arrayDimension > 0) builder.append(" ");

    builder.append(type.category.toString());

    if (type.category == AstTypeCategory.Struct) {
      builder.append(" ");
      builder.append(type.structName);
    }

    return builder.toString();
  }

  private AstAssignment inferAssignment(TypeChecker typeChecker, AstType lhsType, AstAssignment rhsAssignment) {

    if (lhsType.arrayDimension > 0) {

      if (rhsAssignment instanceof AstNew) {
        AstNew _new = (AstNew) rhsAssignment;

        if (_new.arraySizes.size() != lhsType.arrayDimension) {
          reportError(typeChecker, _new.arraySizes.get(_new.arraySizes.size() - 1), "type mismatch between array sizes lhs dim=%d and rhs dim=%d.", lhsType.arrayDimension, _new.arraySizes.size());
        }

        for (AstExpression arrayInitialization : _new.arraySizes) {
          AstType type = inferExpressionType(typeChecker, arrayInitialization);
          if (!isIntegerType(type)) {
            reportError(typeChecker, arrayInitialization, "expected array initialization value to be an integer, but is %s.", typeToString(type));
          }
        }

        return rhsAssignment;
      }

      if (rhsAssignment instanceof AstExpression) {
        AstExpression rhsExpression = (AstExpression) rhsAssignment;

        AstType rhsType = inferExpressionType(typeChecker, rhsExpression);

        if (!doesTypeFit(rhsType, lhsType)) {
          reportError(typeChecker, rhsExpression, "type mismatch between lhs \"%s\" and rhs \"%s\".", typeToString(lhsType), typeToString(rhsType));
        }

        return rhsAssignment;
      }

      throw new CompilerException("unexpected assignment type %s.", rhsAssignment.getClass().getSimpleName());
    }

    if (lhsType.category == AstTypeCategory.Struct) {
      assertIt(lhsType.structName != null);

      if (rhsAssignment instanceof AstNew) {
        return rhsAssignment;
      }

      if (rhsAssignment instanceof AstExpression) {
        AstExpression rhsExpression = (AstExpression) rhsAssignment;

        AstType rhsType = inferExpressionType(typeChecker, rhsExpression);

        if (!doesTypeFit(rhsType, lhsType)) {
          reportError(typeChecker, rhsExpression, "type mismatch between lhs \"%s\" and rhs \"%s\".", typeToString(lhsType), typeToString(rhsType));
        }

        return rhsAssignment;
      }

      throw new CompilerException("unexpected assignment type %s.", rhsAssignment.getClass().getSimpleName());
    }

    // primitive type
    if (rhsAssignment instanceof AstNew) {
      AstNew _new = (AstNew) rhsAssignment;
      reportError(typeChecker, _new, "variable has a primitive type. \"new\" is only possible with structs.");
    }

    if (rhsAssignment instanceof AstExpression) {
      AstExpression rhsExpression = (AstExpression) rhsAssignment;
      AstExpression expression = inferExpression(typeChecker, lhsType, rhsExpression);
      return expression;
    }

    throw new CompilerException("unexpected assignment type %s.", rhsAssignment.getClass().getSimpleName());
  }

  private AstExpression inferExpression(TypeChecker typeChecker, AstType lhsType, AstExpression rhsExpression) {
    AstType rhsType = inferExpressionType(typeChecker, rhsExpression);

    if (doesTypeFit(rhsType, lhsType)) return rhsExpression;

    if (rhsType.category == AstTypeCategory.I32 && isNumberOrCharType(lhsType)) {

      AstLiteral literal = evaluateConstantExpression(rhsExpression);
      if (literal != null) {
        long minValue = getMinIntegerTypeValue(lhsType);
        long maxValue = getMaxIntegerTypeValue(lhsType);

        long value = literal.integerValue;
        if (value < minValue || value > maxValue) reportError(typeChecker, rhsExpression, "Expression %d is out of bounds, [%d; %d] for type %s.", value, minValue, maxValue, lhsType.category);

        AstTypeCast downcast = new AstTypeCast();
        downcast.type = lhsType;
        downcast.expression = rhsExpression;
        downcast.implicit = true;

        return downcast;
      }
    }

    reportError(typeChecker, rhsExpression, "type mismatch. expected type \"%s\", got type: \"%s\".", typeToString(lhsType), typeToString(rhsType));
    return null;
  }

  private long getMinIntegerTypeValue(AstType type) {
    if (type.category == AstTypeCategory.Char) return Character.MIN_VALUE;
    if (type.category == AstTypeCategory.I8) return Byte.MIN_VALUE;
    if (type.category == AstTypeCategory.I16) return Short.MIN_VALUE;
    if (type.category == AstTypeCategory.I32) return Integer.MIN_VALUE;
    if (type.category == AstTypeCategory.I64) return Long.MIN_VALUE;
    throw new CompilerException("invalid type: %s", typeToString(type));
  }

  private long getMaxIntegerTypeValue(AstType type) {
    if (type.category == AstTypeCategory.Char) return Character.MAX_VALUE;
    if (type.category == AstTypeCategory.I8) return Byte.MAX_VALUE;
    if (type.category == AstTypeCategory.I16) return Short.MAX_VALUE;
    if (type.category == AstTypeCategory.I32) return Integer.MAX_VALUE;
    if (type.category == AstTypeCategory.I64) return Long.MAX_VALUE;
    throw new CompilerException("invalid type: %s", typeToString(type));
  }

  private AstLiteral evaluateConstantExpression(AstExpression expression) {

    if (expression instanceof AstParenthesis) {
      AstParenthesis parenthesis = (AstParenthesis) expression;
      return evaluateConstantExpression(parenthesis.body);
    }

    if (expression instanceof AstUnaryOperator) {
      AstUnaryOperator unaryOperator = (AstUnaryOperator) expression;

      AstLiteral result = evaluateConstantExpression(unaryOperator.body);

      boolean integerType = isIntegerType(result.type);
      boolean floatingPointType = isFloatingPointType(result.type);

      assertIt(integerType || floatingPointType);

      switch (unaryOperator.operator) {
        case "+": {
          break;
        }

        case "-": {
          if (integerType) result.integerValue = -result.integerValue;
          result.floatingPointValue = -result.floatingPointValue;
          break;
        }

        case "~": {
          assertIt(integerType);
          result.integerValue = ~result.integerValue;
          break;
        }

        default: {
          throw new CompilerException("invalid unary operator: %s.", unaryOperator.operator);
        }
      }

      return result;
    }

    if (expression instanceof AstBinaryOperator) {
      AstBinaryOperator binaryOperator = (AstBinaryOperator) expression;

      AstLiteral lhs = evaluateConstantExpression(binaryOperator.lhs);

      boolean lhsInteger = isIntegerType(lhs.type);
      boolean lhsFloatingPointType = isFloatingPointType(lhs.type);
      assertIt(lhsInteger || lhsFloatingPointType);

      AstLiteral rhs = evaluateConstantExpression(binaryOperator.rhs);

      boolean rhsInteger = isIntegerType(rhs.type);
      boolean rhsFloatingPointType = isFloatingPointType(rhs.type);
      assertIt(rhsInteger || rhsFloatingPointType);

      AstLiteral result = new AstLiteral();
      result.type = autoPromoteTypeAfterBinaryOperation(lhs.type, rhs.type);

      switch (binaryOperator.operator) {
        case "-": {
          if (lhsFloatingPointType && rhsFloatingPointType) result.floatingPointValue = lhs.floatingPointValue - rhs.floatingPointValue;
          if (lhsFloatingPointType && rhsInteger) result.floatingPointValue = lhs.floatingPointValue - rhs.integerValue;
          if (lhsInteger && rhsFloatingPointType) result.floatingPointValue = lhs.integerValue - rhs.floatingPointValue;
          if (lhsInteger && rhsInteger) result.integerValue = lhs.integerValue - rhs.integerValue;
          break;
        }

        case "+": {
          if (lhsFloatingPointType && rhsFloatingPointType) result.floatingPointValue = lhs.floatingPointValue + rhs.floatingPointValue;
          if (lhsFloatingPointType && rhsInteger) result.floatingPointValue = lhs.floatingPointValue + rhs.integerValue;
          if (lhsInteger && rhsFloatingPointType) result.floatingPointValue = lhs.integerValue + rhs.floatingPointValue;
          if (lhsInteger && rhsInteger) result.integerValue = lhs.integerValue + rhs.integerValue;
          break;
        }

        case "*": {
          if (lhsFloatingPointType && rhsFloatingPointType) result.floatingPointValue = lhs.floatingPointValue * rhs.floatingPointValue;
          if (lhsFloatingPointType && rhsInteger) result.floatingPointValue = lhs.floatingPointValue * rhs.integerValue;
          if (lhsInteger && rhsFloatingPointType) result.floatingPointValue = lhs.integerValue * rhs.floatingPointValue;
          if (lhsInteger && rhsInteger) result.integerValue = lhs.integerValue * rhs.integerValue;
          break;
        }

        case "/": {
          if (lhsFloatingPointType && rhsFloatingPointType) result.floatingPointValue = lhs.floatingPointValue / rhs.floatingPointValue;
          if (lhsFloatingPointType && rhsInteger) result.floatingPointValue = lhs.floatingPointValue / rhs.integerValue;
          if (lhsInteger && rhsFloatingPointType) result.floatingPointValue = lhs.integerValue / rhs.floatingPointValue;
          if (lhsInteger && rhsInteger) result.integerValue = lhs.integerValue / rhs.integerValue;
          break;
        }

        case "%": {
          if (lhsFloatingPointType && rhsFloatingPointType) result.floatingPointValue = lhs.floatingPointValue % rhs.floatingPointValue;
          if (lhsFloatingPointType && rhsInteger) result.floatingPointValue = lhs.floatingPointValue % rhs.integerValue;
          if (lhsInteger && rhsFloatingPointType) result.floatingPointValue = lhs.integerValue % rhs.floatingPointValue;
          if (lhsInteger && rhsInteger) result.integerValue = lhs.integerValue % rhs.integerValue;
          break;
        }

        case "|": {
          assertIt(lhsInteger && rhsInteger);
          result.integerValue = lhs.integerValue | rhs.integerValue;
          break;
        }

        case "&": {
          assertIt(lhsInteger && rhsInteger);
          result.integerValue = lhs.integerValue & rhs.integerValue;
          break;
        }

        case "^": {
          assertIt(lhsInteger && rhsInteger);
          result.integerValue = lhs.integerValue ^ rhs.integerValue;
          break;
        }

        case "<<": {
          assertIt(lhsInteger && rhsInteger);
          result.integerValue = lhs.integerValue << rhs.integerValue;
          break;
        }

        case ">>": {
          assertIt(lhsInteger && rhsInteger);
          result.integerValue = lhs.integerValue >> rhs.integerValue;
          break;
        }

        case ">>>": {
          assertIt(lhsInteger && rhsInteger);
          result.integerValue = lhs.integerValue >>> rhs.integerValue;
          break;
        }

        default: {
          throw new CompilerException("invalid binary operator: %s", binaryOperator.operator);
        }

      }

      // not currently used, but to keep AstLiteral consistent, the field "value" is also set.
      if (isIntegerType(result.type)) {
        result.value = String.format("%d", result.integerValue);
      } else {
        result.value = String.format("%f", result.floatingPointValue);
      }

      return result;
    }

    if (expression instanceof AstLiteral) {
      AstLiteral literal = (AstLiteral) expression;
      return literal;
    }

    if (expression instanceof AstTypeCast) {
      AstTypeCast typecast = (AstTypeCast) expression;

      AstLiteral casted = evaluateConstantExpression(typecast.expression);
      if (casted == null) return null;

      AstLiteral result = new AstLiteral();
      result.type = typecast.type;
      result.value = casted.value;

      boolean castInteger = isIntegerType(typecast.type);
      boolean castFloatingPoint = isFloatingPointType(typecast.type);

      boolean bodyInteger = isIntegerType(casted.type);
      boolean bodyFloatingPoint = isFloatingPointType(casted.type);

      assertIt(castInteger || castFloatingPoint);
      assertIt(bodyInteger || bodyFloatingPoint);

      if (castInteger && bodyInteger) {
        result.integerValue = evaluateIntegerToIntegerCast(typecast.type, casted.integerValue);

      } else if (castInteger && bodyFloatingPoint) {
        result.integerValue = evaluateFloatingPointToIntegerCast(typecast.type, casted.integerValue);

      } else if (castFloatingPoint && bodyInteger) {
        result.floatingPointValue = evaluateIntegerToFloatingPointCast(typecast.type, casted.integerValue);

      } else if (castFloatingPoint && bodyFloatingPoint) {
        result.floatingPointValue = evaluateFloatingPointToFloatingPointCast(typecast.type, casted.floatingPointValue);

      } else {
        throw new CompilerException("broken? non-exhaustive branch.");
      }

      return result;
    }

    throw new CompilerException("unexpected expression type: %s", expression.getClass().getSimpleName());
  }

  private long evaluateIntegerToIntegerCast(AstType type, long value) {
    if (type.category == AstTypeCategory.Char) return (char) value;
    if (type.category == AstTypeCategory.I8) return (byte) value;
    if (type.category == AstTypeCategory.I16) return (short) value;
    if (type.category == AstTypeCategory.I32) return (int) value;
    if (type.category == AstTypeCategory.I64) return (long) value;
    throw new CompilerException("expected integer type, but got %s", type.category);
  }

  private double evaluateIntegerToFloatingPointCast(AstType type, long value) {
    if (type.category == AstTypeCategory.F32) return (float) value;
    if (type.category == AstTypeCategory.F64) return (double) value;
    throw new CompilerException("expected floating point type, but got %s", type.category);
  }

  private long evaluateFloatingPointToIntegerCast(AstType type, double value) {
    if (type.category == AstTypeCategory.Char) return (char) value;
    if (type.category == AstTypeCategory.I8) return (byte) value;
    if (type.category == AstTypeCategory.I16) return (short) value;
    if (type.category == AstTypeCategory.I32) return (int) value;
    if (type.category == AstTypeCategory.I64) return (long) value;
    throw new CompilerException("expected numeric type, but got %s", type.category);
  }

  private double evaluateFloatingPointToFloatingPointCast(AstType type, double value) {
    if (type.category == AstTypeCategory.F32) return (float) value;
    if (type.category == AstTypeCategory.F64) return (double) value;
    throw new CompilerException("expected floating point type, but got %s", type.category);
  }

  private boolean isIntegerType(AstType type) {
    if (type.isVarargs) return false;
    if (type.arrayDimension != 0) return false;
    return type.category == AstTypeCategory.Char || type.category == AstTypeCategory.I8 || type.category == AstTypeCategory.I16 || type.category == AstTypeCategory.I32 || type.category == AstTypeCategory.I64;
  }

  private boolean isFloatingPointType(AstType type) {
    if (type.isVarargs) return false;
    if (type.arrayDimension != 0) return false;
    return type.category == AstTypeCategory.F32 || type.category == AstTypeCategory.F64;
  }

  private Scope createScope() {
    Scope result = new Scope();
    result.variables = new HashMap<>();
    return result;
  }

  private void typeCheckStatements(TypeChecker typeChecker, List<AstStatement> statements) {
    int statementCount = statements.size();
    for (int i = 0; i < statementCount; i++) {
      boolean lastStatement = (i == statementCount - 1);
      AstStatement statement = statements.get(i);
      typeCheckStatement(typeChecker, statement, lastStatement);
    }
  }

  private void typeCheckStatement(TypeChecker typeChecker, AstStatement statement, boolean lastStatementInScope) {
    if (statement instanceof AstFunctionCall) {
      AstFunctionCall functionCall = (AstFunctionCall) statement;
      inferFunctionCallType(typeChecker, functionCall);

    } else if (statement instanceof AstDeclaration) {
      AstDeclaration decl = (AstDeclaration) statement;
      declareLocalVariableInScope(typeChecker, decl);

      // default values
      if (decl.optionalInit == null) {
        decl.optionalInit = getDefaultValueForType(decl.type);
      }

      AstAssignment assignment = inferAssignment(typeChecker, decl.type, decl.optionalInit);
      decl.optionalInit = assignment;

    } else if (statement instanceof AstDefinition) {
      AstDefinition defn = (AstDefinition) statement;

      AstVariable lhs = inferVariable(typeChecker, defn.lhs);
      if (lhs.readOnly) reportError(typeChecker, lhs, "lhs is read-only.");

      AstAssignment assignment = inferAssignment(typeChecker, lhs.type, defn.rhs);
      defn.rhs = assignment;

    } else if (statement instanceof AstReturn) {
      AstReturn _return = (AstReturn) statement;

      if (!lastStatementInScope) reportError(typeChecker, _return.returnExpression, "return should be last statement in scope.");

      FunctionScope functionScope = getFunctionScope(typeChecker);
      AstType functionReturnType = functionScope.function.returnType;
      
      if (_return.returnExpression == null) {
        if (functionReturnType.category != AstTypeCategory.Void) {
          reportError(typeChecker, _return, "got void return statement, expected return type of %s.", typeToString(functionReturnType));
        }
      } else {
        AstExpression expression = inferExpression(typeChecker, functionReturnType, _return.returnExpression);
        _return.returnExpression = expression;
      }

      Scope current = getTopScope(typeChecker);
      current.hasReturnStatement = true;

    } else if (statement instanceof AstIfStatement) {
      AstIfStatement ifStatement = (AstIfStatement) statement;

      AstType conditionType = inferExpressionType(typeChecker, ifStatement.condition);
      if (conditionType.category != AstTypeCategory.Bool) {
        reportError(typeChecker, ifStatement.condition, "condition expression most yield a boolean, but it a yields %s.", typeToString(conditionType));
      }

      Scope ifScope = createScope();
      pushScope(typeChecker, ifScope);
      typeCheckStatements(typeChecker, ifStatement.ifBody);
      popScope(typeChecker);

      Scope elseScope = createScope();
      pushScope(typeChecker, elseScope);
      typeCheckStatements(typeChecker, ifStatement.elseBody);
      popScope(typeChecker);

      Scope current = getTopScope(typeChecker);
      current.hasReturnStatement = ifScope.hasReturnStatement && elseScope.hasReturnStatement;

    } else if (statement instanceof AstWhileLoop) {
      AstWhileLoop whileLoop = (AstWhileLoop) statement;

      AstType conditionType = inferExpressionType(typeChecker, whileLoop.condition);
      if (conditionType.category != AstTypeCategory.Bool) {
        reportError(typeChecker, whileLoop.condition, "condition expression most yield a boolean, but it yields %s.", typeToString(conditionType));
      }

      Scope whileScope = createScope();
      pushScope(typeChecker, whileScope);
      typeCheckStatements(typeChecker, whileLoop.body);
      popScope(typeChecker);

      Scope current = getTopScope(typeChecker);
      current.hasReturnStatement = whileScope.hasReturnStatement;

    } else {
      throw new CompilerException("not a statement: %s", statement.getClass().getSimpleName());
    }
  }

  private int getNumberTypeHierarchicValue(AstTypeCategory category) {
    if (category == AstTypeCategory.I8) return 1;
    if (category == AstTypeCategory.I16) return 2;
    if (category == AstTypeCategory.I32) return 3;
    if (category == AstTypeCategory.I64) return 4;
    if (category == AstTypeCategory.F32) return 5;
    if (category == AstTypeCategory.F64) return 6;
    throw new CompilerException("expected a number type, but got %s.", category);
  }

  private void pushScope(TypeChecker typeChecker, Scope scope) {
    typeChecker.scopes.push(scope);
  }

  private void popScope(TypeChecker typeChecker) {
    typeChecker.scopes.pop();
  }

  private void reportError(TypeChecker typeChecker, Object astNode, String format, Object... args) {
    reportError(typeChecker.sourceCode, getLocation(astNode), format, args);
  }
}
