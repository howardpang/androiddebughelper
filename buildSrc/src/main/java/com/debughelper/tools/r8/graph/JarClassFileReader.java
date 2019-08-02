// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.debughelper.tools.r8.graph;

import static org.objectweb.asm.ClassReader.SKIP_CODE;
import static org.objectweb.asm.ClassReader.SKIP_FRAMES;
import static org.objectweb.asm.Opcodes.ACC_DEPRECATED;
//howard import static org.objectweb.asm.Opcodes.ASM6;
import static org.objectweb.asm.Opcodes.ASM5;

import com.debughelper.tools.r8.ProgramResource.Kind;
import com.debughelper.tools.r8.dex.Constants;
import com.debughelper.tools.r8.graph.ClassAccessFlags;
import com.debughelper.tools.r8.graph.ClassKind;
import com.debughelper.tools.r8.graph.Code;
import com.debughelper.tools.r8.graph.DexAnnotation;
import com.debughelper.tools.r8.graph.DexAnnotationElement;
import com.debughelper.tools.r8.graph.DexAnnotationSet;
import com.debughelper.tools.r8.graph.DexClass;
import com.debughelper.tools.r8.graph.DexEncodedAnnotation;
import com.debughelper.tools.r8.graph.DexEncodedField;
import com.debughelper.tools.r8.graph.DexEncodedMethod;
import com.debughelper.tools.r8.graph.DexField;
import com.debughelper.tools.r8.graph.DexItemFactory;
import com.debughelper.tools.r8.graph.DexMethod;
import com.debughelper.tools.r8.graph.DexProgramClass;
import com.debughelper.tools.r8.graph.DexType;
import com.debughelper.tools.r8.graph.DexTypeList;
import com.debughelper.tools.r8.graph.DexValue;
import com.debughelper.tools.r8.graph.DexValue.DexValueAnnotation;
import com.debughelper.tools.r8.graph.DexValue.DexValueArray;
import com.debughelper.tools.r8.graph.DexValue.DexValueBoolean;
import com.debughelper.tools.r8.graph.DexValue.DexValueByte;
import com.debughelper.tools.r8.graph.DexValue.DexValueChar;
import com.debughelper.tools.r8.graph.DexValue.DexValueDouble;
import com.debughelper.tools.r8.graph.DexValue.DexValueEnum;
import com.debughelper.tools.r8.graph.DexValue.DexValueFloat;
import com.debughelper.tools.r8.graph.DexValue.DexValueInt;
import com.debughelper.tools.r8.graph.DexValue.DexValueLong;
import com.debughelper.tools.r8.graph.DexValue.DexValueNull;
import com.debughelper.tools.r8.graph.DexValue.DexValueShort;
import com.debughelper.tools.r8.graph.DexValue.DexValueString;
import com.debughelper.tools.r8.graph.DexValue.DexValueType;
import com.debughelper.tools.r8.graph.EnclosingMethodAttribute;
import com.debughelper.tools.r8.graph.FieldAccessFlags;
import com.debughelper.tools.r8.graph.InnerClassAttribute;
import com.debughelper.tools.r8.graph.JarApplicationReader;
import com.debughelper.tools.r8.graph.JarCode;
import com.debughelper.tools.r8.graph.LazyCfCode;
import com.debughelper.tools.r8.graph.MethodAccessFlags;
import com.debughelper.tools.r8.graph.ParameterAnnotationsList;
import com.debughelper.tools.r8.ProgramResource;
import com.debughelper.tools.r8.errors.CompilationError;
import com.debughelper.tools.r8.errors.Unreachable;
import com.debughelper.tools.r8.origin.Origin;
import com.debughelper.tools.r8.utils.InternalOptions;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.Attribute;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;
import org.objectweb.asm.TypePath;

/**
 * Java/Jar class reader for constructing dex/graph structure.
 */
public class JarClassFileReader {

  private static final byte[] CLASSFILE_HEADER = ByteBuffer.allocate(4).putInt(0xCAFEBABE).array();

  // Hidden ASM "synthetic attribute" bit we need to clear.
  private static final int ACC_SYNTHETIC_ATTRIBUTE = 0x40000;
  // Descriptor used by ASM for missing annotations.
  public static final String SYNTHETIC_ANNOTATION = "Ljava/lang/Synthetic;";

  private final com.debughelper.tools.r8.graph.JarApplicationReader application;
  private final Consumer<com.debughelper.tools.r8.graph.DexClass> classConsumer;

  public JarClassFileReader(
          com.debughelper.tools.r8.graph.JarApplicationReader application, Consumer<com.debughelper.tools.r8.graph.DexClass> classConsumer) {
    this.application = application;
    this.classConsumer = classConsumer;
  }

  public void read(com.debughelper.tools.r8.origin.Origin origin, com.debughelper.tools.r8.graph.ClassKind classKind, InputStream input) throws IOException {
    if (!input.markSupported()) {
      input = new BufferedInputStream(input);
    }
    byte[] header = new byte[CLASSFILE_HEADER.length];
    input.mark(header.length);
    int size = 0;
    while (size < header.length) {
      int read = input.read(header, size, header.length - size);
      if (read < 0) {
        throw new com.debughelper.tools.r8.errors.CompilationError("Invalid empty classfile", origin);
      }
      size += read;
    }
    if (!Arrays.equals(CLASSFILE_HEADER, header)) {
      throw new com.debughelper.tools.r8.errors.CompilationError("Invalid classfile header", origin);
    }
    input.reset();

    ClassReader reader = new ClassReader(input);
    reader.accept(
        new CreateDexClassVisitor(origin, classKind, reader.b, application, classConsumer),
        SKIP_FRAMES | SKIP_CODE);
  }

  private static int cleanAccessFlags(int access) {
    // Clear the "synthetic attribute" and "deprecated" attribute-flags if present.
    return access & ~ACC_SYNTHETIC_ATTRIBUTE & ~ACC_DEPRECATED;
  }

  public static com.debughelper.tools.r8.graph.MethodAccessFlags createMethodAccessFlags(String name, int access) {
    boolean isConstructor =
        name.equals(Constants.INSTANCE_INITIALIZER_NAME)
            || name.equals(Constants.CLASS_INITIALIZER_NAME);
    return com.debughelper.tools.r8.graph.MethodAccessFlags.fromCfAccessFlags(cleanAccessFlags(access), isConstructor);
  }

  private static AnnotationVisitor createAnnotationVisitor(String desc, boolean visible,
      List<com.debughelper.tools.r8.graph.DexAnnotation> annotations,
      com.debughelper.tools.r8.graph.JarApplicationReader application) {
    assert annotations != null;
    int visiblity = visible ? com.debughelper.tools.r8.graph.DexAnnotation.VISIBILITY_RUNTIME : com.debughelper.tools.r8.graph.DexAnnotation.VISIBILITY_BUILD;
    return new CreateAnnotationVisitor(application, (names, values) ->
        annotations.add(new com.debughelper.tools.r8.graph.DexAnnotation(visiblity,
            createEncodedAnnotation(desc, names, values, application))));
  }

  private static com.debughelper.tools.r8.graph.DexEncodedAnnotation createEncodedAnnotation(String desc,
                                                                                         List<DexString> names, List<com.debughelper.tools.r8.graph.DexValue> values, com.debughelper.tools.r8.graph.JarApplicationReader application) {
    assert (names == null && values.isEmpty())
        || (names != null && !names.isEmpty() && names.size() == values.size());
    com.debughelper.tools.r8.graph.DexAnnotationElement[] elements = new com.debughelper.tools.r8.graph.DexAnnotationElement[values.size()];
    for (int i = 0; i < values.size(); i++) {
      elements[i] = new com.debughelper.tools.r8.graph.DexAnnotationElement(names.get(i), values.get(i));
    }
    return new DexEncodedAnnotation(application.getTypeFromDescriptor(desc), elements);
  }

  private static class CreateDexClassVisitor extends ClassVisitor {

    private final com.debughelper.tools.r8.origin.Origin origin;
    private final com.debughelper.tools.r8.graph.ClassKind classKind;
    private final com.debughelper.tools.r8.graph.JarApplicationReader application;
    private final Consumer<com.debughelper.tools.r8.graph.DexClass> classConsumer;
    private final ReparseContext context = new ReparseContext();

    // DexClass data.
    private int version;
    private com.debughelper.tools.r8.graph.DexType type;
    private com.debughelper.tools.r8.graph.ClassAccessFlags accessFlags;
    private com.debughelper.tools.r8.graph.DexType superType;
    private DexTypeList interfaces;
    private DexString sourceFile;
    private com.debughelper.tools.r8.graph.EnclosingMethodAttribute enclosingMember = null;
    private final List<com.debughelper.tools.r8.graph.InnerClassAttribute> innerClasses = new ArrayList<>();
    private List<com.debughelper.tools.r8.graph.DexAnnotation> annotations = null;
    private List<com.debughelper.tools.r8.graph.DexAnnotationElement> defaultAnnotations = null;
    private final List<com.debughelper.tools.r8.graph.DexEncodedField> staticFields = new ArrayList<>();
    private final List<com.debughelper.tools.r8.graph.DexEncodedField> instanceFields = new ArrayList<>();
    private final List<com.debughelper.tools.r8.graph.DexEncodedMethod> directMethods = new ArrayList<>();
    private final List<com.debughelper.tools.r8.graph.DexEncodedMethod> virtualMethods = new ArrayList<>();

    public CreateDexClassVisitor(
        Origin origin,
        com.debughelper.tools.r8.graph.ClassKind classKind,
        byte[] classCache,
        com.debughelper.tools.r8.graph.JarApplicationReader application,
        Consumer<com.debughelper.tools.r8.graph.DexClass> classConsumer) {
      super(ASM5);
      this.origin = origin;
      this.classKind = classKind;
      this.classConsumer = classConsumer;
      this.context.classCache = classCache;
      this.application = application;
    }

    @Override
    public void visitInnerClass(String name, String outerName, String innerName, int access) {
      innerClasses.add(
          new InnerClassAttribute(
              access,
              application.getTypeFromName(name),
              outerName == null ? null : application.getTypeFromName(outerName),
              innerName == null ? null : application.getString(innerName)));
    }

    @Override
    public void visitOuterClass(String owner, String name, String desc) {
      // This is called for anonymous and local inner classes defined in classes or in methods.
      assert enclosingMember == null;
      com.debughelper.tools.r8.graph.DexType ownerType = application.getTypeFromName(owner);
      enclosingMember =
          name == null
              ? new com.debughelper.tools.r8.graph.EnclosingMethodAttribute(ownerType)
              : new EnclosingMethodAttribute(application.getMethod(ownerType, name, desc));
    }

    private String illegalClassFilePrefix(com.debughelper.tools.r8.graph.ClassAccessFlags accessFlags, String name) {
      return "Illegal class file: "
          + (accessFlags.isInterface() ? "Interface" : "Class")
          + " "
          + name;
    }

    private String illegalClassFilePostfix(int version) {
      return "Class file version " + version;
    }

    private String illegalClassFileMessage(
            com.debughelper.tools.r8.graph.ClassAccessFlags accessFlags, String name, int version, String message) {
      return illegalClassFilePrefix(accessFlags, name)
          + " " + message
          + ". " + illegalClassFilePostfix(version) + ".";
    }

    @Override
    public void visit(int version, int access, String name, String signature, String superName,
        String[] interfaces) {
      this.version = version;
      accessFlags = ClassAccessFlags.fromCfAccessFlags(cleanAccessFlags(access));
      type = application.getTypeFromName(name);
      // Check if constraints from
      // https://docs.oracle.com/javase/specs/jvms/se7/html/jvms-4.html#jvms-4.1 are met.
      if (!accessFlags.areValid(getMajorVersion(), name.endsWith("/package-info"))) {
        throw new com.debughelper.tools.r8.errors.CompilationError(
            illegalClassFileMessage(accessFlags, name, version,
                "has invalid access flags. Found: " + accessFlags.toString()), origin);
      }
      if (superName == null && !name.equals(Constants.JAVA_LANG_OBJECT_NAME)) {
        throw new com.debughelper.tools.r8.errors.CompilationError(
            illegalClassFileMessage(accessFlags, name, version,
                "is missing a super type"), origin);
      }
      if (accessFlags.isInterface()
          && !Objects.equals(superName, Constants.JAVA_LANG_OBJECT_NAME)) {
        throw new CompilationError(
            illegalClassFileMessage(accessFlags, name, version,
                "must extend class java.lang.Object. Found: " + Objects.toString(superName)),
            origin);
      }
      assert superName != null || name.equals(Constants.JAVA_LANG_OBJECT_NAME);
      superType = superName == null ? null : application.getTypeFromName(superName);
      this.interfaces = application.getTypeListFromNames(interfaces);
      if (signature != null && !signature.isEmpty()) {
        addAnnotation(com.debughelper.tools.r8.graph.DexAnnotation.createSignatureAnnotation(signature, application.getFactory()));
      }
    }

    @Override
    public void visitSource(String source, String debug) {
      if (source != null) {
        sourceFile = application.getString(source);
      }
      if (debug != null) {
        getAnnotations().add(
            com.debughelper.tools.r8.graph.DexAnnotation.createSourceDebugExtensionAnnotation(
                new DexValueString(application.getString(debug)), application.getFactory()));
      }
    }

    @Override
    public FieldVisitor visitField(
        int access, String name, String desc, String signature, Object value) {
      return new CreateFieldVisitor(this, access, name, desc, signature, value);
    }

    @Override
    public MethodVisitor visitMethod(
        int access, String name, String desc, String signature, String[] exceptions) {
      return new CreateMethodVisitor(access, name, desc, signature, exceptions, this);
    }

    @Override
    public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
      return createAnnotationVisitor(desc, visible, getAnnotations(), application);
    }

    @Override
    public AnnotationVisitor visitTypeAnnotation(int typeRef, TypePath typePath, String desc,
        boolean visible) {
      // Java 8 type annotations are not supported by Dex, thus ignore them.
      return null;
    }

    @Override
    public void visitAttribute(Attribute attr) {
      // Unknown attribute must only be ignored
    }

    @Override
    public void visitEnd() {
      if (defaultAnnotations != null) {
        addAnnotation(com.debughelper.tools.r8.graph.DexAnnotation.createAnnotationDefaultAnnotation(
            type, defaultAnnotations, application.getFactory()));
      }
      DexClass clazz =
          classKind.create(
              type,
              ProgramResource.Kind.CF,
              origin,
              accessFlags,
              superType,
              interfaces,
              sourceFile,
              enclosingMember,
              innerClasses,
              createAnnotationSet(annotations),
              staticFields.toArray(new com.debughelper.tools.r8.graph.DexEncodedField[staticFields.size()]),
              instanceFields.toArray(new com.debughelper.tools.r8.graph.DexEncodedField[instanceFields.size()]),
              directMethods.toArray(new com.debughelper.tools.r8.graph.DexEncodedMethod[directMethods.size()]),
              virtualMethods.toArray(new com.debughelper.tools.r8.graph.DexEncodedMethod[virtualMethods.size()]),
              application.getFactory().getSkipNameValidationForTesting());
      if (clazz.isProgramClass()) {
        context.owner = clazz.asProgramClass();
        clazz.asProgramClass().setClassFileVersion(version);
      }
      classConsumer.accept(clazz);
    }

    private void addDefaultAnnotation(String name, com.debughelper.tools.r8.graph.DexValue value) {
      if (defaultAnnotations == null) {
        defaultAnnotations = new ArrayList<>();
      }
      defaultAnnotations.add(new DexAnnotationElement(application.getString(name), value));
    }

    private void addAnnotation(com.debughelper.tools.r8.graph.DexAnnotation annotation) {
      getAnnotations().add(annotation);
    }

    private List<com.debughelper.tools.r8.graph.DexAnnotation> getAnnotations() {
      if (annotations == null) {
        annotations = new ArrayList<>();
      }
      return annotations;
    }

    private int getMajorVersion() {
      return version & 0xFFFF;
    }

    private int getMinorVersion() {
      return ((version >> 16) & 0xFFFF);
    }
  }

  private static com.debughelper.tools.r8.graph.DexAnnotationSet createAnnotationSet(List<com.debughelper.tools.r8.graph.DexAnnotation> annotations) {
    return annotations == null || annotations.isEmpty()
        ? com.debughelper.tools.r8.graph.DexAnnotationSet.empty()
        : new com.debughelper.tools.r8.graph.DexAnnotationSet(annotations.toArray(new com.debughelper.tools.r8.graph.DexAnnotation[annotations.size()]));
  }

  private static class CreateFieldVisitor extends FieldVisitor {

    private final CreateDexClassVisitor parent;
    private final int access;
    private final String name;
    private final String desc;
    private final Object value;
    private List<com.debughelper.tools.r8.graph.DexAnnotation> annotations = null;

    public CreateFieldVisitor(CreateDexClassVisitor parent,
        int access, String name, String desc, String signature, Object value) {
      super(ASM5);
      this.parent = parent;
      this.access = access;
      this.name = name;
      this.desc = desc;
      this.value = value;
      if (signature != null && !signature.isEmpty()) {
        addAnnotation(com.debughelper.tools.r8.graph.DexAnnotation.createSignatureAnnotation(
            signature, parent.application.getFactory()));
      }
    }

    @Override
    public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
      return createAnnotationVisitor(desc, visible, getAnnotations(), parent.application);
    }

    @Override
    public AnnotationVisitor visitTypeAnnotation(int typeRef, TypePath typePath, String desc,
        boolean visible) {
      // Java 8 type annotations are not supported by Dex, thus ignore them.
      return null;
    }

    @Override
    public void visitEnd() {
      com.debughelper.tools.r8.graph.FieldAccessFlags flags = FieldAccessFlags.fromCfAccessFlags(cleanAccessFlags(access));
      DexField dexField = parent.application.getField(parent.type, name, desc);
      com.debughelper.tools.r8.graph.DexAnnotationSet annotationSet = createAnnotationSet(annotations);
      com.debughelper.tools.r8.graph.DexValue staticValue = flags.isStatic() ? getStaticValue(value, dexField.type) : null;
      com.debughelper.tools.r8.graph.DexEncodedField field = new DexEncodedField(dexField, flags, annotationSet, staticValue);
      if (flags.isStatic()) {
        parent.staticFields.add(field);
      } else {
        parent.instanceFields.add(field);
      }
    }

    private com.debughelper.tools.r8.graph.DexValue getStaticValue(Object value, com.debughelper.tools.r8.graph.DexType type) {
      if (value == null) {
        return null;
      }
      DexItemFactory factory = parent.application.getFactory();
      if (type == factory.booleanType) {
        int i = (Integer) value;
        assert 0 <= i && i <= 1;
        return DexValueBoolean.create(i == 1);
      }
      if (type == factory.byteType) {
        return DexValueByte.create(((Integer) value).byteValue());
      }
      if (type == factory.shortType) {
        return DexValueShort.create(((Integer) value).shortValue());
      }
      if (type == factory.charType) {
        return DexValueChar.create((char) ((Integer) value).intValue());
      }
      if (type == factory.intType) {
        return DexValueInt.create((Integer) value);
      }
      if (type == factory.floatType) {
        return DexValueFloat.create((Float) value);
      }
      if (type == factory.longType) {
        return DexValueLong.create((Long) value);
      }
      if (type == factory.doubleType) {
        return DexValueDouble.create((Double) value);
      }
      if (type == factory.stringType) {
        return new DexValueString(factory.createString((String) value));
      }
      throw new com.debughelper.tools.r8.errors.Unreachable("Unexpected static-value type " + type);
    }

    private void addAnnotation(com.debughelper.tools.r8.graph.DexAnnotation annotation) {
      getAnnotations().add(annotation);
    }

    private List<com.debughelper.tools.r8.graph.DexAnnotation> getAnnotations() {
      if (annotations == null) {
        annotations = new ArrayList<>();
      }
      return annotations;
    }
  }

  private static class CreateMethodVisitor extends MethodVisitor {

    private final String name;
    final CreateDexClassVisitor parent;
    private final int parameterCount;
    private List<com.debughelper.tools.r8.graph.DexAnnotation> annotations = null;
    private com.debughelper.tools.r8.graph.DexValue defaultAnnotation = null;
    private int fakeParameterAnnotations = 0;
    private List<List<com.debughelper.tools.r8.graph.DexAnnotation>> parameterAnnotationsLists = null;
    private List<com.debughelper.tools.r8.graph.DexValue> parameterNames = null;
    private List<com.debughelper.tools.r8.graph.DexValue> parameterFlags = null;
    final DexMethod method;
    final MethodAccessFlags flags;
    com.debughelper.tools.r8.graph.Code code = null;

    public CreateMethodVisitor(int access, String name, String desc, String signature,
        String[] exceptions, CreateDexClassVisitor parent) {
      super(ASM5);
      this.name = name;
      this.parent = parent;
      this.method = parent.application.getMethod(parent.type, name, desc);
      this.flags = createMethodAccessFlags(name, access);
      parameterCount = com.debughelper.tools.r8.graph.JarApplicationReader.getArgumentCount(desc);
      if (exceptions != null && exceptions.length > 0) {
        com.debughelper.tools.r8.graph.DexValue[] values = new com.debughelper.tools.r8.graph.DexValue[exceptions.length];
        for (int i = 0; i < exceptions.length; i++) {
          values[i] = new DexValueType(parent.application.getTypeFromName(exceptions[i]));
        }
        addAnnotation(com.debughelper.tools.r8.graph.DexAnnotation.createThrowsAnnotation(
            values, parent.application.getFactory()));
      }
      if (signature != null && !signature.isEmpty()) {
        addAnnotation(com.debughelper.tools.r8.graph.DexAnnotation.createSignatureAnnotation(
            signature, parent.application.getFactory()));
      }
    }

    @Override
    public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
      return createAnnotationVisitor(desc, visible, getAnnotations(), parent.application);
    }

    @Override
    public AnnotationVisitor visitAnnotationDefault() {
      return new CreateAnnotationVisitor(parent.application, (names, elements) -> {
        assert elements.size() == 1;
        defaultAnnotation = elements.get(0);
      });
    }

    @Override
    public AnnotationVisitor visitTypeAnnotation(int typeRef, TypePath typePath, String desc,
        boolean visible) {
      // Java 8 type annotations are not supported by Dex, thus ignore them.
      return null;
    }

    @Override
    public AnnotationVisitor visitParameterAnnotation(int parameter, String desc, boolean visible) {
      // ASM decided to workaround a javac bug that incorrectly deals with synthesized parameter
      // annotations. However, that leads us to have different behavior than javac+jvm and
      // dx+art. The workaround is to use a non-existing descriptor "Ljava/lang/Synthetic;" for
      // exactly this case. In order to remove the workaround we ignore all annotations
      // with that descriptor. If javac is fixed, the ASM workaround will not be hit and we will
      // never see this non-existing annotation descriptor. ASM uses the same check to make
      // sure to undo their workaround for the javac bug in their MethodWriter.
      if (desc.equals(SYNTHETIC_ANNOTATION)) {
        // We can iterate through all the parameters twice. Once for visible and once for
        // invisible parameter annotations. We only record the number of fake parameter
        // annotations once.
        if (parameterAnnotationsLists == null) {
          fakeParameterAnnotations++;
        }
        return null;
      }
      if (parameterAnnotationsLists == null) {
        int adjustedParameterCount = parameterCount - fakeParameterAnnotations;
        parameterAnnotationsLists = new ArrayList<>(adjustedParameterCount);
        for (int i = 0; i < adjustedParameterCount; i++) {
          parameterAnnotationsLists.add(new ArrayList<>());
        }
      }
      assert mv == null;
      return createAnnotationVisitor(desc, visible,
          parameterAnnotationsLists.get(parameter - fakeParameterAnnotations), parent.application);
    }

    @Override
    public AnnotationVisitor visitInsnAnnotation(int typeRef, TypePath typePath, String desc,
        boolean visible) {
      // Java 8 type annotations are not supported by Dex, thus ignore them.
      return null;
    }

    @Override
    public AnnotationVisitor visitLocalVariableAnnotation(int typeRef, TypePath typePath,
        Label[] start, Label[] end, int[] index, String desc, boolean visible) {
      // Java 8 type annotations are not supported by Dex, thus ignore them.
      return null;
    }

    @Override
    public AnnotationVisitor visitTryCatchAnnotation(int typeRef, TypePath typePath, String desc,
        boolean visible) {
      // Java 8 type annotations are not supported by Dex, thus ignore them.
      return null;
    }

    @Override
    public void visitParameter(String name, int access) {
      if (parameterNames == null) {
        assert parameterFlags == null;
        parameterNames = new ArrayList<>(parameterCount);
        parameterFlags = new ArrayList<>(parameterCount);
      }
      parameterNames.add(new DexValueString(parent.application.getFactory().createString(name)));
      parameterFlags.add(DexValueInt.create(access));
      super.visitParameter(name, access);
    }

    @Override
    public void visitCode() {
      throw new com.debughelper.tools.r8.errors.Unreachable("visitCode() should not be called when SKIP_CODE is set");
    }

    @Override
    public void visitEnd() {
      if (!flags.isAbstract() && !flags.isNative() && parent.classKind == ClassKind.PROGRAM) {
        if (parent.application.options.enableCfFrontend) {
          code = new LazyCfCode(method, parent.origin, parent.context, parent.application);
        } else {
          code = new JarCode(method, parent.origin, parent.context, parent.application);
        }
      }
      com.debughelper.tools.r8.graph.ParameterAnnotationsList annotationsList;
      if (parameterAnnotationsLists == null) {
        annotationsList = com.debughelper.tools.r8.graph.ParameterAnnotationsList.empty();
      } else {
        com.debughelper.tools.r8.graph.DexAnnotationSet[] sets = new DexAnnotationSet[parameterAnnotationsLists.size()];
        for (int i = 0; i < parameterAnnotationsLists.size(); i++) {
          sets[i] = createAnnotationSet(parameterAnnotationsLists.get(i));
        }
        annotationsList = new ParameterAnnotationsList(sets, fakeParameterAnnotations);
      }
      InternalOptions internalOptions = parent.application.options;
      if (parameterNames != null && internalOptions.canUseParameterNameAnnotations()) {
        assert parameterFlags != null;
        if (parameterNames.size() != parameterCount) {
          internalOptions.warningInvalidParameterAnnotations(
              method, parent.origin, parameterCount, parameterNames.size());
        }
        getAnnotations().add(com.debughelper.tools.r8.graph.DexAnnotation.createMethodParametersAnnotation(
            parameterNames.toArray(new com.debughelper.tools.r8.graph.DexValue[parameterNames.size()]),
            parameterFlags.toArray(new com.debughelper.tools.r8.graph.DexValue[parameterFlags.size()]),
            parent.application.getFactory()));
      }
      com.debughelper.tools.r8.graph.DexEncodedMethod dexMethod =
          new DexEncodedMethod(
              method,
              flags,
              createAnnotationSet(annotations),
              annotationsList,
              code,
              parent.version);
      if (flags.isStatic() || flags.isConstructor() || flags.isPrivate()) {
        parent.directMethods.add(dexMethod);
      } else {
        parent.virtualMethods.add(dexMethod);
      }
      if (defaultAnnotation != null) {
        parent.addDefaultAnnotation(name, defaultAnnotation);
      }
    }

    private List<com.debughelper.tools.r8.graph.DexAnnotation> getAnnotations() {
      if (annotations == null) {
        annotations = new ArrayList<>();
      }
      return annotations;
    }

    private void addAnnotation(DexAnnotation annotation) {
      getAnnotations().add(annotation);
    }
  }

  private static class CreateAnnotationVisitor extends AnnotationVisitor {

    private final com.debughelper.tools.r8.graph.JarApplicationReader application;
    private final BiConsumer<List<DexString>, List<com.debughelper.tools.r8.graph.DexValue>> onVisitEnd;
    private List<DexString> names = null;
    private final List<com.debughelper.tools.r8.graph.DexValue> values = new ArrayList<>();

    public CreateAnnotationVisitor(
            JarApplicationReader application, BiConsumer<List<DexString>, List<com.debughelper.tools.r8.graph.DexValue>> onVisitEnd) {
      super(ASM5);
      this.application = application;
      this.onVisitEnd = onVisitEnd;
    }

    @Override
    public void visit(String name, Object value) {
      addElement(name, getDexValue(value));
    }

    @Override
    public void visitEnum(String name, String desc, String value) {
      DexType owner = application.getTypeFromDescriptor(desc);
      addElement(name, new DexValueEnum(application.getField(owner, value, desc)));
    }

    @Override
    public AnnotationVisitor visitAnnotation(String name, String desc) {
      return new CreateAnnotationVisitor(application, (names, values) ->
          addElement(name, new DexValueAnnotation(
              createEncodedAnnotation(desc, names, values, application))));
    }

    @Override
    public AnnotationVisitor visitArray(String name) {
      return new CreateAnnotationVisitor(application, (names, values) -> {
        assert names == null;
        addElement(name, new DexValueArray(values.toArray(new com.debughelper.tools.r8.graph.DexValue[values.size()])));
      });
    }

    @Override
    public void visitEnd() {
      onVisitEnd.accept(names, values);
    }

    private void addElement(String name, com.debughelper.tools.r8.graph.DexValue value) {
      if (name != null) {
        if (names == null) {
          names = new ArrayList<>();
        }
        names.add(application.getString(name));
      }
      values.add(value);
    }

    private static DexValueArray getDexValueArray(Object value) {
      if (value instanceof byte[]) {
        byte[] values = (byte[]) value;
        com.debughelper.tools.r8.graph.DexValue[] elements = new com.debughelper.tools.r8.graph.DexValue[values.length];
        for (int i = 0; i < values.length; i++) {
          elements[i] = DexValueByte.create(values[i]);
        }
        return new DexValueArray(elements);
      } else if (value instanceof boolean[]) {
        boolean[] values = (boolean[]) value;
        com.debughelper.tools.r8.graph.DexValue[] elements = new com.debughelper.tools.r8.graph.DexValue[values.length];
        for (int i = 0; i < values.length; i++) {
          elements[i] = DexValueBoolean.create(values[i]);
        }
        return new DexValueArray(elements);
      } else if (value instanceof char[]) {
        char[] values = (char[]) value;
        com.debughelper.tools.r8.graph.DexValue[] elements = new com.debughelper.tools.r8.graph.DexValue[values.length];
        for (int i = 0; i < values.length; i++) {
          elements[i] = DexValueChar.create(values[i]);
        }
        return new DexValueArray(elements);
      } else if (value instanceof short[]) {
        short[] values = (short[]) value;
        com.debughelper.tools.r8.graph.DexValue[] elements = new com.debughelper.tools.r8.graph.DexValue[values.length];
        for (int i = 0; i < values.length; i++) {
          elements[i] = DexValueShort.create(values[i]);
        }
        return new DexValueArray(elements);
      } else if (value instanceof int[]) {
        int[] values = (int[]) value;
        com.debughelper.tools.r8.graph.DexValue[] elements = new com.debughelper.tools.r8.graph.DexValue[values.length];
        for (int i = 0; i < values.length; i++) {
          elements[i] = DexValueInt.create(values[i]);
        }
        return new DexValueArray(elements);
      } else if (value instanceof long[]) {
        long[] values = (long[]) value;
        com.debughelper.tools.r8.graph.DexValue[] elements = new com.debughelper.tools.r8.graph.DexValue[values.length];
        for (int i = 0; i < values.length; i++) {
          elements[i] = DexValueLong.create(values[i]);
        }
        return new DexValueArray(elements);
      } else if (value instanceof float[]) {
        float[] values = (float[]) value;
        com.debughelper.tools.r8.graph.DexValue[] elements = new com.debughelper.tools.r8.graph.DexValue[values.length];
        for (int i = 0; i < values.length; i++) {
          elements[i] = DexValueFloat.create(values[i]);
        }
        return new DexValueArray(elements);
      } else if (value instanceof double[]) {
        double[] values = (double[]) value;
        com.debughelper.tools.r8.graph.DexValue[] elements = new com.debughelper.tools.r8.graph.DexValue[values.length];
        for (int i = 0; i < values.length; i++) {
          elements[i] = DexValueDouble.create(values[i]);
        }
        return new DexValueArray(elements);
      }
      throw new Unreachable("Unexpected type of annotation value: " + value);
    }

    private DexValue getDexValue(Object value) {
      if (value == null) {
        return DexValueNull.NULL;
      }
      if (value instanceof Byte) {
        return DexValueByte.create((Byte) value);
      } else if (value instanceof Boolean) {
        return DexValueBoolean.create((Boolean) value);
      } else if (value instanceof Character) {
        return DexValueChar.create((Character) value);
      } else if (value instanceof Short) {
        return DexValueShort.create((Short) value);
      } else if (value instanceof Integer) {
        return DexValueInt.create((Integer) value);
      } else if (value instanceof Long) {
        return DexValueLong.create((Long) value);
      } else if (value instanceof Float) {
        return DexValueFloat.create((Float) value);
      } else if (value instanceof Double) {
        return DexValueDouble.create((Double) value);
      } else if (value instanceof String) {
        return new DexValueString(application.getString((String) value));
      } else if (value instanceof Type) {
        return new DexValueType(application.getTypeFromDescriptor(((Type) value).getDescriptor()));
      }
      return getDexValueArray(value);
    }
  }

  public static class ReparseContext {

    // This will hold the content of the whole class. Once all the methods of the class are swapped
    // from this to the actual JarCode, no other references would be left and the content can be
    // GC'd.
    public byte[] classCache;
    public DexProgramClass owner;
    public final List<Code> codeList = new ArrayList<>();
  }
}
