package com.debughelper.tools.r8.graph;

import com.debughelper.tools.r8.dex.ApplicationReader;
import com.debughelper.tools.r8.graph.DexApplication;
import com.debughelper.tools.r8.graph.DexByteCodeWriter;
import com.debughelper.tools.r8.graph.DexEncodedField;
import com.debughelper.tools.r8.graph.DexEncodedMethod;
import com.debughelper.tools.r8.graph.DexProgramClass;
import com.debughelper.tools.r8.graph.DexType;
import com.debughelper.tools.r8.errors.CompilationError;
import com.debughelper.tools.r8.utils.AndroidApp;
import com.debughelper.tools.r8.utils.InternalOptions;
import com.debughelper.tools.r8.utils.Timing;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;

public class SmaliWriter extends DexByteCodeWriter {

  public SmaliWriter(com.debughelper.tools.r8.graph.DexApplication application,
                     com.debughelper.tools.r8.utils.InternalOptions options) {
    super(application, options);
  }

  /** Return smali source for the application code. */
  public static String smali(AndroidApp application, InternalOptions options) {
    ByteArrayOutputStream os = new ByteArrayOutputStream();
    try (PrintStream ps = new PrintStream(os)) {
      DexApplication dexApplication = new ApplicationReader(application, options,
          new Timing("SmaliWriter"))
          .read();
      SmaliWriter writer = new SmaliWriter(dexApplication, options);
      writer.write(ps);
    } catch (IOException | ExecutionException e) {
      throw new CompilationError("Failed to generate smali sting", e);
    }
    return new String(os.toByteArray(), StandardCharsets.UTF_8);
  }

  @Override
  String getFileEnding() {
    return ".smali";
  }

  @Override
  void writeClassHeader(com.debughelper.tools.r8.graph.DexProgramClass clazz, PrintStream ps) {
    ps.append(".class ");
    ps.append(clazz.accessFlags.toSmaliString());
    ps.append(" ");
    ps.append(clazz.type.toSmaliString());
    ps.append("\n\n");
    if (clazz.type != application.dexItemFactory.objectType) {
      ps.append(".super ");
      ps.append(clazz.superType.toSmaliString());
      ps.append("\n");
      for (DexType iface : clazz.interfaces.values) {
        ps.append(".implements ");
        ps.append(iface.toSmaliString());
        ps.append("\n");
      }
    }
  }

  @Override
  void writeClassFooter(DexProgramClass clazz, PrintStream ps) {
    ps.append("# End of class ");
    ps.append(clazz.type.toSmaliString());
    ps.append("\n");
  }

  @Override
  void writeMethod(DexEncodedMethod method, PrintStream ps) {
    ps.append("\n");
    ps.append(method.toSmaliString(application.getProguardMap()));
    ps.append("\n");
  }

  @Override
  void writeField(DexEncodedField field, PrintStream ps) {
    // Not yet implemented.
  }
}
