package pack;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public interface CompilerModule extends ParserModule, TypeCheckerModule, JavaConverterModule, GraphvizModule {

  public CompilerSettings settings = new CompilerSettings();

  static public class CompilerSettings {
    public boolean writeCompilerModulesToFile;
    public boolean writeOutputToFile;
    // public boolean generateStaticFunctions;
    //public boolean singleFileOutputoutputSingleFile;
    public boolean catchableErrors;

    public boolean writeAstToFile;
    public String graphvizPath;
  }

  default void compile(String mainFilepath) {
    AstProgram astProgram = parseUnits(mainFilepath);

    if (CompilerModule.settings.writeAstToFile) {
      String outputPath = "./res/output/ast.png";
      generateGraphvizGraph_fromAst(CompilerModule.settings.graphvizPath, outputPath, astProgram);
    }
    
    typeCheck(astProgram);

    String javaCode = convertToJavaCode(astProgram);

    if (CompilerModule.settings.writeOutputToFile) {
      writeStringToFile(javaCode, "./res/output/ABCProgramRunMe.java");
    }
    
    if (CompilerModule.settings.writeCompilerModulesToFile) {
      Path preloadPath = Paths.get("./res/modules/Preload.txt");
      String preloadModule = readFileToString(preloadPath);
      writeStringToFile(preloadModule, "./res/output/Preload.java");
      
      Path runtimeSupportPath = Paths.get("./res/modules/RuntimeSupport.txt");
      String runtimeSupportModule = readFileToString(runtimeSupportPath);
      writeStringToFile(runtimeSupportModule, "./res/output/RuntimeSupport.java");
    }
  }

  static public class GraphvizIdGenerator {
    public int id;
  }

  private String getUniqueNodeId(GraphvizIdGenerator generator) {
    int id = generator.id;
    generator.id += 1;
    return String.format("n%d", id);
  }

  private void generateGraphvizGraph_fromAst(String graphvizPath, String outputPath, AstProgram astProgram) {
    AstCompilationUnit ast = astProgram.compilationUnits.get(0);
    
    GraphvizBuilder gvz = graphvizBuilder();
    GraphvizIdGenerator generator = new GraphvizIdGenerator();

    gvz.nodes.add(graphvizNode("program", "Program"));
    gvz.nodes.add(graphvizNode("fns", "Function List"));
    gvz.nodes.add(graphvizNode("structs", "Struct List"));

    gvz.edges.add(graphvizEdge("program", "fns"));
    gvz.edges.add(graphvizEdge("program", "structs"));

    for (AstFunction function : ast.functions) {
      String returnType = getReadableType(function.returnType);
      String functionId = String.format("fn_%s", function.name);
      String functionLabel = String.format("Function\\nname = %s\\nreturn = %s\\nparams = %d", function.name, returnType, function.parameters.size());

      gvz.nodes.add(graphvizNode(functionId, functionLabel));
      gvz.edges.add(graphvizEdge("fns", functionId));

      for (AstStatement statement : function.bodyStatements) {
        generateGraphvizStatement(gvz, generator, functionId, statement);
      }
    }

    for (AstStruct struct : ast.structs) {
      String structId = String.format("struct_%s", struct.name);
      String structLabel = String.format("Struct\\nname = %s\\nparams = %d", struct.name, struct.fields.size());

      gvz.nodes.add(graphvizNode(structId, structLabel));
      gvz.edges.add(graphvizEdge("structs", structId));

      for (AstStructField field : struct.fields) {
        String type = getReadableType(field.type);
        String fieldId = getUniqueNodeId(generator);
        String fieldLabel = String.format("Field\\nname = %s\\ntype = %s", field.name, type);

        gvz.nodes.add(graphvizNode(fieldId, fieldLabel));
        gvz.edges.add(graphvizEdge(structId, fieldId));
      }
    }

    String input = buildGraphvizGraph(gvz);

    generateGraphvizGraph_fromString(graphvizPath, input, outputPath);
  }

  private void generateGraphvizStatement(GraphvizBuilder gvz, GraphvizIdGenerator generator, String functionId, AstStatement statement) {
    if (statement instanceof AstDeclaration) {
      AstDeclaration decl = (AstDeclaration) statement;

      String type = getReadableType(decl.type);
      String declId = getUniqueNodeId(generator); // String.format("decl_%s_%s", function.name, decl.identifier);
      String declLabel = String.format("Declaration\\ntype = %s\\nidentifier = %s", type, decl.identifier);

      gvz.nodes.add(graphvizNode(declId, declLabel));
      gvz.edges.add(graphvizEdge(functionId, declId));

      // optionalInit first set after the typechecking to the default value if it hasn't been set explicitly in source code.
      if (decl.optionalInit != null) {
        String expressionId = getUniqueNodeId(generator);
        String expressionLabel = String.format("Expression");

        generateGraphvizAssignment(gvz, generator, decl.optionalInit, expressionId);

        gvz.nodes.add(graphvizNode(expressionId, expressionLabel));
        gvz.edges.add(graphvizEdge(declId, expressionId));
      }

    } else if (statement instanceof AstDefinition) {
      AstDefinition defn = (AstDefinition) statement;

      String identifier = getReadableIdentifier(defn.lhs);
      String defId = getUniqueNodeId(generator);
      String defLabel = String.format("Definition\\nidentifier = %s", identifier);

      gvz.nodes.add(graphvizNode(defId, defLabel));
      gvz.edges.add(graphvizEdge(functionId, defId));

      String expressionId = getUniqueNodeId(generator);
      String expressionLabel = String.format("Expression");

      generateGraphvizAssignment(gvz, generator, defn.rhs, expressionId);

      gvz.nodes.add(graphvizNode(expressionId, expressionLabel));
      gvz.edges.add(graphvizEdge(defId, expressionId));

    } else if (statement instanceof AstReturn) {
      AstReturn _return = (AstReturn) statement;

      String returnId = getUniqueNodeId(generator);

      gvz.nodes.add(graphvizNode(returnId, "Return"));
      gvz.edges.add(graphvizEdge(functionId, returnId));

      String expressionId = getUniqueNodeId(generator);
      String expressionLabel = String.format("Expression");

      generateGraphvizExpression(gvz, generator, _return.returnExpression, expressionId);

      gvz.nodes.add(graphvizNode(expressionId, expressionLabel));
      gvz.edges.add(graphvizEdge(returnId, expressionId));

    } else if (statement instanceof AstFunctionCall) {
      AstFunctionCall functionCall = (AstFunctionCall) statement;
      generateGraphvizFunctionCall(gvz, generator, functionId, functionCall);

    } else if (statement instanceof AstIfStatement) {
      AstIfStatement ifStatement = (AstIfStatement) statement;

      String ifStatementId = getUniqueNodeId(generator);
      gvz.nodes.add(graphvizNode(ifStatementId, "If"));
      gvz.edges.add(graphvizEdge(functionId, ifStatementId));

      String ifBlockId = getUniqueNodeId(generator);
      gvz.nodes.add(graphvizNode(ifBlockId, "If-Block"));
      gvz.edges.add(graphvizEdge(ifStatementId, ifBlockId));
      for (AstStatement ifStatementBody : ifStatement.ifBody) {
        generateGraphvizStatement(gvz, generator, ifBlockId, ifStatementBody);
      }

      if (ifStatement.elseBody.size() > 0) {
        String elseBlockId = getUniqueNodeId(generator);
        gvz.nodes.add(graphvizNode(elseBlockId, "Else-Block"));
        gvz.edges.add(graphvizEdge(ifStatementId, elseBlockId));
        for (AstStatement elseStatementBody : ifStatement.elseBody) {
          generateGraphvizStatement(gvz, generator, elseBlockId, elseStatementBody);
        }
      }

    } else if (statement instanceof AstWhileLoop) {
      AstWhileLoop whileLoop = (AstWhileLoop) statement;

      String whileLoopId = getUniqueNodeId(generator);
      gvz.nodes.add(graphvizNode(whileLoopId, "While"));
      gvz.edges.add(graphvizEdge(functionId, whileLoopId));

      // @TODO: refactor into function
      String expressionId = getUniqueNodeId(generator);
      String expressionLabel = String.format("Condition");

      generateGraphvizExpression(gvz, generator, whileLoop.condition, expressionId);

      gvz.nodes.add(graphvizNode(expressionId, expressionLabel));
      gvz.edges.add(graphvizEdge(whileLoopId, expressionId));

      for (AstStatement statementBody : whileLoop.body) {
        generateGraphvizStatement(gvz, generator, whileLoopId, statementBody);
      }

    } else {
      throw new CompilerException("unsupported statement %s", statement.getClass().getSimpleName());
    }
  }

  private String getReadableIdentifier(AstVariable identifier) {
    StringBuilder builder = new StringBuilder();

    builder.append(identifier.name);

    while (identifier.child != null) {
      builder.append(".");
      builder.append(identifier.child.name);
      identifier = identifier.child;
    }

    return builder.toString();
  }

  private String getReadableType(AstType type) {
    // @TODO: arrays
    if (type.category == AstTypeCategory.Struct) return String.format("%s (%s)", type.structName, type.category.name());
    return type.category.name();
  }

  private void generateGraphvizFunctionCall(GraphvizBuilder gvz, GraphvizIdGenerator generator, String functionId, AstFunctionCall functionCall) {
    String functionCallId = getUniqueNodeId(generator);
    String functionCallLabel = String.format("Function Call\\nname = %s\\n args = %s", functionCall.name, functionCall.arguments.size());

    gvz.nodes.add(graphvizNode(functionCallId, functionCallLabel));
    gvz.edges.add(graphvizEdge(functionId, functionCallId));
  }

  private void generateGraphvizAssignment(GraphvizBuilder gvz, GraphvizIdGenerator generator, AstAssignment assignment, String parentId) {
    if (assignment instanceof AstExpression) {
      AstExpression expression = (AstExpression) assignment;
      generateGraphvizExpression(gvz, generator, expression, parentId);

    } else if (assignment instanceof AstNew) {
      // @TODO.

    } else {
      throw new CompilerException("unexpected AstAssignment: %s", assignment.getClass().getSimpleName());
    }
  }

  private void generateGraphvizExpression(GraphvizBuilder gvz, GraphvizIdGenerator generator, AstExpression expression, String parentId) {
    if (expression instanceof AstLiteral) {
      AstLiteral literal = (AstLiteral) expression;

      String value = literal.value.replace("\"", "\'");

      String id = getUniqueNodeId(generator);

      gvz.nodes.add(graphvizNode(id, value));
      gvz.edges.add(graphvizEdge(parentId, id));

    } else if (expression instanceof AstUnaryOperator) {
      AstUnaryOperator operator = (AstUnaryOperator) expression;

      String id = getUniqueNodeId(generator);

      gvz.nodes.add(graphvizNode(id, operator.operator));
      gvz.edges.add(graphvizEdge(parentId, id));

      generateGraphvizExpression(gvz, generator, operator.body, id);

    } else if (expression instanceof AstBinaryOperator) {
      AstBinaryOperator operator = (AstBinaryOperator) expression;

      String id = getUniqueNodeId(generator);

      gvz.nodes.add(graphvizNode(id, operator.operator));
      gvz.edges.add(graphvizEdge(parentId, id));

      generateGraphvizExpression(gvz, generator, operator.lhs, id);
      generateGraphvizExpression(gvz, generator, operator.rhs, id);

    } else if (expression instanceof AstParenthesis) {
      AstParenthesis parenthesis = (AstParenthesis) expression;

      String id = getUniqueNodeId(generator);

      gvz.nodes.add(graphvizNode(id, "( )"));
      gvz.edges.add(graphvizEdge(parentId, id));

      generateGraphvizExpression(gvz, generator, parenthesis.body, id);

    } else if (expression instanceof AstVariable) {
      AstVariable variable = (AstVariable) expression;

      String id = getUniqueNodeId(generator);
      String chain = getReadableIdentifier(variable);
      String label = String.format("Variable\\nname = %s", chain);

      gvz.nodes.add(graphvizNode(id, label));
      gvz.edges.add(graphvizEdge(parentId, id));

    } else if (expression instanceof AstParenthesis) {
      AstParenthesis parenthesis = (AstParenthesis) expression;

      String id = getUniqueNodeId(generator);

      gvz.nodes.add(graphvizNode(id, "( )"));
      gvz.edges.add(graphvizEdge(parentId, id));

      generateGraphvizExpression(gvz, generator, parenthesis.body, id);

    } else if (expression instanceof AstFunctionCall) {
      AstFunctionCall functionCall = (AstFunctionCall) expression;
      generateGraphvizFunctionCall(gvz, generator, parentId, functionCall);

    } else if (expression instanceof AstTypeCast) {
      AstTypeCast typeCast = (AstTypeCast) expression;

      String id = getUniqueNodeId(generator);

      String type = getReadableType(typeCast.type);
      String label = String.format("cast(%s)", type);
      gvz.nodes.add(graphvizNode(id, label));
      gvz.edges.add(graphvizEdge(parentId, id));

      generateGraphvizExpression(gvz, generator, typeCast.expression, id);

      // @TODO
    } else {
      throw new CompilerException("unexpected AstExpression: %s", expression.getClass().getSimpleName());
    }
  }

  private void writeStringToFile(String string, String filepath) {
    try {
      Files.writeString(Paths.get(filepath), string);
    } catch (IOException e) {
      e.printStackTrace();
    }
  }
}
