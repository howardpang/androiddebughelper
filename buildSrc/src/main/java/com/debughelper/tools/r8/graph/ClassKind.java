package com.debughelper.tools.r8.graph;

import com.debughelper.tools.r8.graph.ClassAccessFlags;
import com.debughelper.tools.r8.graph.DexAnnotationSet;
import com.debughelper.tools.r8.graph.DexClass;
import com.debughelper.tools.r8.graph.DexClasspathClass;
import com.debughelper.tools.r8.graph.DexEncodedField;
import com.debughelper.tools.r8.graph.DexEncodedMethod;
import com.debughelper.tools.r8.graph.DexProgramClass;
import com.debughelper.tools.r8.graph.DexType;
import com.debughelper.tools.r8.graph.DexTypeList;
import com.debughelper.tools.r8.graph.EnclosingMethodAttribute;
import com.debughelper.tools.r8.graph.InnerClassAttribute;
import com.debughelper.tools.r8.ProgramResource;
import com.debughelper.tools.r8.origin.Origin;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** Kind of the application class. Can be program, classpath or library. */
public enum ClassKind {
  PROGRAM(DexProgramClass::new, com.debughelper.tools.r8.graph.DexClass::isProgramClass),
  CLASSPATH(DexClasspathClass::new, com.debughelper.tools.r8.graph.DexClass::isClasspathClass),
  LIBRARY(DexLibraryClass::new, com.debughelper.tools.r8.graph.DexClass::isLibraryClass);

  private interface Factory {
    com.debughelper.tools.r8.graph.DexClass create(
        com.debughelper.tools.r8.graph.DexType type,
        com.debughelper.tools.r8.ProgramResource.Kind kind,
        com.debughelper.tools.r8.origin.Origin origin,
        com.debughelper.tools.r8.graph.ClassAccessFlags accessFlags,
        com.debughelper.tools.r8.graph.DexType superType,
        com.debughelper.tools.r8.graph.DexTypeList interfaces,
        DexString sourceFile,
        com.debughelper.tools.r8.graph.EnclosingMethodAttribute enclosingMember,
        List<com.debughelper.tools.r8.graph.InnerClassAttribute> innerClasses,
        com.debughelper.tools.r8.graph.DexAnnotationSet annotations,
        com.debughelper.tools.r8.graph.DexEncodedField[] staticFields,
        com.debughelper.tools.r8.graph.DexEncodedField[] instanceFields,
        com.debughelper.tools.r8.graph.DexEncodedMethod[] directMethods,
        com.debughelper.tools.r8.graph.DexEncodedMethod[] virtualMethods,
        boolean skipNameValidationForTesting);
  }

  private final Factory factory;
  private final Predicate<com.debughelper.tools.r8.graph.DexClass> check;

  ClassKind(Factory factory, Predicate<com.debughelper.tools.r8.graph.DexClass> check) {
    this.factory = factory;
    this.check = check;
  }

  public com.debughelper.tools.r8.graph.DexClass create(
      com.debughelper.tools.r8.graph.DexType type,
      ProgramResource.Kind kind,
      Origin origin,
      ClassAccessFlags accessFlags,
      DexType superType,
      DexTypeList interfaces,
      DexString sourceFile,
      EnclosingMethodAttribute enclosingMember,
      List<InnerClassAttribute> innerClasses,
      DexAnnotationSet annotations,
      com.debughelper.tools.r8.graph.DexEncodedField[] staticFields,
      DexEncodedField[] instanceFields,
      com.debughelper.tools.r8.graph.DexEncodedMethod[] directMethods,
      DexEncodedMethod[] virtualMethods,
      boolean skipNameValidationForTesting) {
    return factory.create(
        type,
        kind,
        origin,
        accessFlags,
        superType,
        interfaces,
        sourceFile,
        enclosingMember,
        innerClasses,
        annotations,
        staticFields,
        instanceFields,
        directMethods,
        virtualMethods,
        skipNameValidationForTesting);
  }

  public boolean isOfKind(com.debughelper.tools.r8.graph.DexClass clazz) {
    return check.test(clazz);
  }

  public <T extends com.debughelper.tools.r8.graph.DexClass> Consumer<DexClass> bridgeConsumer(Consumer<T> consumer) {
    return clazz -> {
      assert isOfKind(clazz);
      @SuppressWarnings("unchecked") T specialized = (T) clazz;
      consumer.accept(specialized);
    };
  }
}
