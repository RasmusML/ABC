package pack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import pack.ParserModule.AstAssignment;
import pack.ParserModule.AstBinaryOperator;
import pack.ParserModule.AstCompilationUnit;
import pack.ParserModule.AstDeclaration;
import pack.ParserModule.AstDefinition;
import pack.ParserModule.AstExpression;
import pack.ParserModule.AstFunction;
import pack.ParserModule.AstFunctionCall;
import pack.ParserModule.AstIfStatement;
import pack.ParserModule.AstLiteral;
import pack.ParserModule.AstNew;
import pack.ParserModule.AstParameterDeclaration;
import pack.ParserModule.AstParenthesis;
import pack.ParserModule.AstProgram;
import pack.ParserModule.AstReturn;
import pack.ParserModule.AstStatement;
import pack.ParserModule.AstStruct;
import pack.ParserModule.AstStructField;
import pack.ParserModule.AstType;
import pack.ParserModule.AstTypeCast;
import pack.ParserModule.AstTypeCategory;
import pack.ParserModule.AstUnaryOperator;
import pack.ParserModule.AstVariable;
import pack.ParserModule.AstWhileLoop;

public interface JavaConverterModule {

  static public class JavaConverter {
    public StringBuilder builder;
    public int indents;
    public int spacesPerIndent;
  }

  default String convertToJavaCode(AstProgram astProgram) {
    JavaConverter converter = new JavaConverter();
    converter.builder = new StringBuilder();
    converter.spacesPerIndent = 2;
    converter.indents = 0;

    AstCompilationUnit mainUnit = astProgram.compilationUnits.get(0);

    emitLine(converter, "package output;");
    emitEmptyLine(converter);
    emitLine(converter, "public class ABCProgramRunMe {");

    indent(converter);
    {
      emitLine(converter, "public static void main(String[] args) {");

      if (mainUnit.hasProgramEntry) {

        indent(converter);
        {
          emitLine(converter, "new MainModule() {}.main();");
        }
        unindent(converter);

      }

      emitLine(converter, "};");
      emitEmptyLine(converter);

      String javaLibraryBindings = getJavaLibraryBindings(mainUnit.javaLibraryDependencyNames);

      emitLine(converter, "static public interface MainModule %s{", javaLibraryBindings);

      indent(converter);
      {

        for (AstStruct struct : mainUnit.structs) {
          if (struct.hasJavaLibraryBinding) continue;

          emitEmptyLine(converter);

          emitLine(converter, "static public class %s {", struct.name);

          indent(converter);
          {
            for (AstStructField field : struct.fields) {
              String javaType = getJavaTypeString(field.type);
              emitLine(converter, "public %s %s;", javaType, field.name);
            }
          }
          unindent(converter);

          emitLine(converter, "}");
        }

        for (AstFunction function : mainUnit.functions) {
          if (function.hasJavaLibraryBinding) continue;

          emitEmptyLine(converter);

          String functionHeader = getJavaFunctionHeader(function);
          emitLine(converter, "%s {", functionHeader);

          indent(converter);
          {
            for (AstStatement statement : function.bodyStatements) {
              emitJavaStatement(converter, statement);
            }
          }
          unindent(converter);

          emitLine(converter, "}");
        }
      }
      unindent(converter);

      emitLine(converter, "}");
    }
    unindent(converter);

    emitLine(converter, "}");

    String result = converter.builder.toString();

    return result;
  }

  default String getJavaLibraryBindings(Set<String> javaLibraryNames) {
    StringBuilder builder = new StringBuilder();

    List<String> orderedJavaLibraryNames = new ArrayList<>();
    orderedJavaLibraryNames.addAll(javaLibraryNames);

    Collections.sort(orderedJavaLibraryNames);

    if (orderedJavaLibraryNames.size() > 0) {
      builder.append("extends ");

      String firstLib = orderedJavaLibraryNames.get(0);
      builder.append(firstLib);

      for (int i = 1; i < orderedJavaLibraryNames.size(); i++) {
        String lib = orderedJavaLibraryNames.get(i);

        builder.append(", ");
        builder.append(lib);
      }

      builder.append(" ");
    }

    return builder.toString();
  }

  private String getJavaAssignment(AstAssignment assignment, AstType type) {
    if (assignment instanceof AstExpression) {
      AstExpression expression = (AstExpression) assignment;
      return getJavaExpressionString(expression);
    }

    if (assignment instanceof AstNew) {
      AstNew _new = (AstNew) assignment;

      if (type.arrayDimension > 0) {
        StringBuilder builder = new StringBuilder();
        String javaBaseType = getJavaBaseTypeString(type);

        builder.append("new ");
        builder.append(javaBaseType);

        for (AstExpression arraySubExpression : _new.arraySizes) {
          builder.append("[");
          builder.append(getJavaExpressionString(arraySubExpression));
          builder.append("]");
        }

        return builder.toString();
      }

      String javaBaseType = getJavaBaseTypeString(type);
      return String.format("new %s()", javaBaseType);
    }

    throw new CompilerException("not handled assignment: %s", assignment.getClass().getSimpleName());
  }

  private void emitJavaStatement(JavaConverter converter, AstStatement statement) {
    if (statement instanceof AstDeclaration) {
      AstDeclaration decl = (AstDeclaration) statement;
      String javaType = getJavaTypeString(decl.type);

      String initializationValue = getJavaAssignment(decl.optionalInit, decl.type);
      emitLine(converter, "%s %s = %s;", javaType, decl.identifier, initializationValue);
      return;
    }

    if (statement instanceof AstDefinition) {
      AstDefinition defn = (AstDefinition) statement;

      AstVariable lhs = defn.lhs;
      while (lhs.child != null) lhs = lhs.child;

      String javaLhs = getJavaExpressionString(defn.lhs);
      String javaRhs = getJavaAssignment(defn.rhs, lhs.type);
      emitLine(converter, "%s = %s;", javaLhs, javaRhs);
      return;
    }

    if (statement instanceof AstReturn) {
      AstReturn _return = (AstReturn) statement;
      
      if (_return.returnExpression == null) { // void
        emitLine(converter, "return;");
        return;
      }
      
      String javaReturn = getJavaExpressionString(_return.returnExpression);
      emitLine(converter, "return %s;", javaReturn);
      return;
    }

    if (statement instanceof AstFunctionCall) {
      AstFunctionCall functionCall = (AstFunctionCall) statement;
      String javaFunctionCall = getJavaFunctionCallString(functionCall);
      emitLine(converter, javaFunctionCall + ";");
      return;
    }

    if (statement instanceof AstIfStatement) {
      AstIfStatement ifStatement = (AstIfStatement) statement;

      String javaConditionExpression = getJavaExpressionString(ifStatement.condition);
      String condition = String.format("if (%s) {", javaConditionExpression);
      emitLine(converter, condition);

      indent(converter);
      for (AstStatement statementInBody : ifStatement.ifBody) {
        emitJavaStatement(converter, statementInBody);
      }
      unindent(converter);

      if (ifStatement.elseBody.size() == 0) {
        emitLine(converter, "}");
        return;
      }

      emitLine(converter, "} else {");

      indent(converter);
      for (AstStatement statementInBody : ifStatement.elseBody) {
        emitJavaStatement(converter, statementInBody);
      }
      unindent(converter);

      emitLine(converter, "}");

      return;
    }

    if (statement instanceof AstWhileLoop) {
      AstWhileLoop whileLoop = (AstWhileLoop) statement;

      String javaConditionExpression = getJavaExpressionString(whileLoop.condition);
      String condition = String.format("while (%s) {", javaConditionExpression);
      emitLine(converter, condition);

      indent(converter);
      for (AstStatement statementInBody : whileLoop.body) {
        emitJavaStatement(converter, statementInBody);
      }
      unindent(converter);

      emitLine(converter, "}");
      return;
    }

    throw new CompilerException("unsupported statement: %s", statement.getClass().getName());
  }

  private String getJavaFunctionHeader(AstFunction function) {
    StringBuilder builder = new StringBuilder();

    String returnType = getJavaTypeString(function.returnType);
    String start = String.format("default %s %s", returnType, function.name);
    builder.append(start);
    builder.append("(");

    for (int i = 0; i < function.parameters.size() - 1; i++) {
      AstParameterDeclaration parameter = function.parameters.get(i);

      String javaParameterType = getJavaTypeString(parameter.type);
      String javaParameter = String.format("%s %s", javaParameterType, parameter.name);
      builder.append(javaParameter);
      builder.append(", ");
    }

    if (function.parameters.size() > 0) {
      AstParameterDeclaration parameter = function.parameters.get(function.parameters.size() - 1);

      String javaParameterType = getJavaTypeString(parameter.type);
      String javaParameter = String.format("%s %s", javaParameterType, parameter.name);
      builder.append(javaParameter);
    }

    builder.append(")");

    return builder.toString();
  }

  private String getJavaFunctionCallString(AstFunctionCall functionCall) {
    StringBuilder builder = new StringBuilder();
    builder.append(functionCall.name);
    builder.append("(");

    for (int i = 0; i < functionCall.arguments.size() - 1; i++) {
      AstExpression arg = functionCall.arguments.get(i);
      String javaExpression = getJavaExpressionString(arg);
      builder.append(javaExpression);
      builder.append(", ");
    }

    if (functionCall.arguments.size() > 0) {
      AstExpression argN = functionCall.arguments.get(functionCall.arguments.size() - 1);
      String javaExpression = getJavaExpressionString(argN);
      builder.append(javaExpression);
    }

    builder.append(")");

    return builder.toString();
  }

  private String getJavaExpressionString(AstExpression expression) {

    if (expression instanceof AstLiteral) {
      AstLiteral literal = (AstLiteral) expression;
      if (literal.value.equals("nil")) return "null";

      if (literal.type.category == AstTypeCategory.String) {
        return String.format("\"%s\"", literal.value);
      }

      return literal.value;

    } else if (expression instanceof AstBinaryOperator) {
      AstBinaryOperator operator = (AstBinaryOperator) expression;
      String lhs = getJavaExpressionString(operator.lhs);
      String rhs = getJavaExpressionString(operator.rhs);
      return String.format("(%s %s %s)", lhs, operator.operator, rhs);  // parenthesis for safety

    } else if (expression instanceof AstUnaryOperator) {
      AstUnaryOperator operator = (AstUnaryOperator) expression;
      String rhs = getJavaExpressionString(operator.body);
      return String.format("(%s%s)", operator.operator, rhs); // parenthesis for safety

    } else if (expression instanceof AstParenthesis) {
      AstParenthesis parenthesis = (AstParenthesis) expression;
      String body = getJavaExpressionString(parenthesis.body);
      return String.format("(%s)", body);

    } else if (expression instanceof AstVariable) {
      AstVariable variable = (AstVariable) expression;

      StringBuilder builder = new StringBuilder();
      String firstName = variable.name;
      builder.append(firstName);

      for (AstExpression arrayExpression : variable.arrayExpressions) {
        String javaExpression = getJavaExpressionString(arrayExpression);

        builder.append("[");
        builder.append(javaExpression);
        builder.append("]");
      }

      if (variable.child != null) {
        String javaExpression = getJavaExpressionString(variable.child);

        builder.append(".");
        builder.append(javaExpression);
      }

      return builder.toString();

    } else if (expression instanceof AstFunctionCall) {
      AstFunctionCall functionCall = (AstFunctionCall) expression;
      return getJavaFunctionCallString(functionCall);

    } else if (expression instanceof AstTypeCast) {
      AstTypeCast typecast = (AstTypeCast) expression;
      String javaType = getJavaTypeString(typecast.type);
      String javaExpression = getJavaExpressionString(typecast.expression);
      return String.format("(%s)%s", javaType, javaExpression);

    } else {
      throw new CompilerException("unexpected AstExpression: %s", expression.getClass().getSimpleName());
    }
  }

  private String getJavaBaseTypeString(AstType type) {
    if (type.category == AstTypeCategory.Void) return "void";
    if (type.category == AstTypeCategory.Bool) return "boolean";
    if (type.category == AstTypeCategory.I8) return "byte";
    if (type.category == AstTypeCategory.I16) return "short";
    if (type.category == AstTypeCategory.I32) return "int";
    if (type.category == AstTypeCategory.I64) return "long";
    if (type.category == AstTypeCategory.F32) return "float";
    if (type.category == AstTypeCategory.F64) return "double";
    if (type.category == AstTypeCategory.Char) return "char";
    if (type.category == AstTypeCategory.String) return "String";
    if (type.category == AstTypeCategory.Any) return "Object";
    if (type.category == AstTypeCategory.Struct) return type.structName;
    throw new CompilerException("failed to convert type \"%s\" to a java type.", type.category);
  }

  private String getJavaTypeString(AstType type) {
    String baseType = getJavaBaseTypeString(type);
    String brackets = getJavaArrayBrackets(type.arrayDimension);
    String varargs = getJavaArrayVarargs(type.isVarargs);
    return String.format("%s%s%s", baseType, brackets, varargs);
  }

  default String getJavaArrayVarargs(boolean isVarargs) {
    if (isVarargs) return "...";
    return "";
  }

  private void emitLine(JavaConverter converter, String format, Object... args) {
    String indention = getTextIndentation(converter.indents * converter.spacesPerIndent);
    String text = String.format(format, args);
    String indentedText = String.format("%s%s", indention, text);
    converter.builder.append(indentedText);
    converter.builder.append("\n");
  }

  private void emitLine(JavaConverter converter, String line) {
    emitLine(converter, "%s", line);
  }

  private void emitEmptyLine(JavaConverter converter) {
    converter.builder.append("\n");
  }

  private void indent(JavaConverter converter) {
    converter.indents += 1;
  }

  private void unindent(JavaConverter converter) {
    converter.indents -= 1;
  }

  private String getJavaArrayBrackets(int arrayDimension) {
    return repeat("[]", arrayDimension);
  }

  private String getTextIndentation(int spaceCount) {
    return repeat(" ", spaceCount);
  }

  private String repeat(String str, int count) {
    StringBuilder builder = new StringBuilder();

    for (int i = 0; i < count; i++) {
      builder.append(str);
    }

    return builder.toString();
  }

}
