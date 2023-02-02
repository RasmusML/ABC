package pack;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

public interface GraphvizModule {
  
  static public class GraphvizNode {
    public String id;
    public String label;
  }

  static public class GraphvizEdge {
    public String fromId;
    public String toId;
  }

  static public class GraphvizBuilder {
    public List<GraphvizNode> nodes;
    public List<GraphvizEdge> edges;
  }
  
  default public String buildGraphvizGraph(GraphvizBuilder gvz) {
    StringBuilder builder = new StringBuilder();

    builder.append("digraph {\n");

    for (GraphvizNode node : gvz.nodes) {
      String textNode = String.format("%s[label=\"%s\"];", node.id, node.label);
      String indentedTextNode = String.format("  %s", textNode);

      builder.append(indentedTextNode);
      builder.append("\n");
    }

    for (GraphvizEdge edge : gvz.edges) {
      String textEdge = String.format("%s->%s", edge.fromId, edge.toId);
      String indentedTextEdge = String.format("  %s", textEdge);

      builder.append(indentedTextEdge);
      builder.append("\n");
    }

    builder.append("}");

    return builder.toString();
  }

  default GraphvizBuilder graphvizBuilder() {
    GraphvizBuilder result = new GraphvizBuilder();
    result.nodes = new ArrayList<>();
    result.edges = new ArrayList<>();
    return result;
  }

  default GraphvizNode graphvizNode(String id, String label) {
    GraphvizNode result = new GraphvizNode();
    result.id = id;
    result.label = label;
    return result;
  }

  default GraphvizEdge graphvizEdge(String fromId, String toId) {
    GraphvizEdge result = new GraphvizEdge();
    result.fromId = fromId;
    result.toId = toId;
    return result;
  }


  default void generateGraphvizGraph_fromString(String graphvizPath, String input, String outputPath) {
    try {
      // @TODO: cleanup. Don't create a temporary file, just feed input into dot.exe repl mode directly.
      File file = new File("__graph.dot");
      file.createNewFile();

      BufferedWriter writer = new BufferedWriter(new FileWriter(file));
      writer.write(input);
      writer.flush();
      writer.close();

      generateGraphvizGraph_fromFile(graphvizPath, file.getAbsolutePath(), outputPath);

      // @Note: only gets deleted if we didn't get an error buffer
      file.delete();

    } catch (IOException e) {
      throw new UserException("failed to create graphviz graph");
    }
  }

  default void generateGraphvizGraph_fromFile(String graphvizPath, String inputPath, String outputPath) {
    String outputFormat = getFileExtension(outputPath);
    if (outputFormat == null)
      throw new UserException("expected outputPath \"%s\", to contain a file extension, but didn't", outputPath);

    String command = getDotCommand(graphvizPath, inputPath, outputPath, outputFormat);
    shellExecute(command, true);
  }

  default String getFileExtension(String filePath) {
    String[] format = filePath.split("\\.");
    if (format.length < 2) return null;
    return format[format.length - 1];
  }

  default String getDotCommand(String graphvizPath, String input, String output, String format) {
    return String.format("\"%s\\dot\" -T%s -o %s %s", graphvizPath, format, output, input);
  }

  default int shellExecute(String command) {
    return shellExecute(command, false);
  }

  default int shellExecute(String command, boolean verbose) {
    try {
      String shellPrefix = getShellPrefix();
      String fullCommand = String.format("%s %s", shellPrefix, command);

      Process process = Runtime.getRuntime().exec(fullCommand);

      if (verbose) {
        String stdout = digestStream(process.getInputStream());
        if (stdout.length() > 0) System.out.printf("%s", stdout);

        String stderr = digestStream(process.getErrorStream());
        if (stderr.length() > 0) System.out.printf("%s", stderr);
      }

      int returnCode = process.waitFor();
      return returnCode;

    } catch (IOException | InterruptedException e) {
      throw new UserException("failed to execute \"%s\"", command);
    }
  }

  default String digestStream(InputStream stream) {
    try {
      BufferedReader stdoutReader = new BufferedReader(new InputStreamReader(stream));
      StringBuilder stdoutBuffer = new StringBuilder();

      String line;
      while ((line = stdoutReader.readLine()) != null) {
        stdoutBuffer.append(line);
        stdoutBuffer.append("\n");
      }

      String result = stdoutBuffer.toString();

      // remove last newline
      if (result.length() > 0) result = result.substring(0, result.length() - 1);

      return result;

    } catch (IOException e) {
      throw new UserException("failed to get stream content");
    }
  }

  default String getShellPrefix() {
    String os = System.getProperty("os.name");
    if (os.toLowerCase().startsWith("windows")) return "cmd.exe /c";
    throw new UserException("unsupported OS: %s", os);
  }
}
