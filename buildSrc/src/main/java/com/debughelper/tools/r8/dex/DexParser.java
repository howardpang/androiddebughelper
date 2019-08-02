// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.debughelper.tools.r8.dex;

import static com.debughelper.tools.r8.utils.EncodedValueUtils.parseDouble;
import static com.debughelper.tools.r8.utils.EncodedValueUtils.parseFloat;
import static com.debughelper.tools.r8.utils.EncodedValueUtils.parseSigned;
import static com.debughelper.tools.r8.utils.EncodedValueUtils.parseUnsigned;

import com.debughelper.tools.r8.ProgramResource.Kind;
import com.debughelper.tools.r8.graph.DexCode;
import com.debughelper.tools.r8.graph.DexCode.Try;
import com.debughelper.tools.r8.graph.DexCode.TryHandler;
import com.debughelper.tools.r8.graph.DexCode.TryHandler.TypeAddrPair;
import com.debughelper.tools.r8.graph.DexMemberAnnotation;
import com.debughelper.tools.r8.graph.DexMemberAnnotation.DexFieldAnnotation;
import com.debughelper.tools.r8.graph.DexMemberAnnotation.DexMethodAnnotation;
import com.debughelper.tools.r8.graph.DexMemberAnnotation.DexParameterAnnotation;
import com.debughelper.tools.r8.graph.DexMethodHandle;
import com.debughelper.tools.r8.graph.DexMethodHandle.MethodHandleType;
import com.debughelper.tools.r8.graph.DexValue;
import com.debughelper.tools.r8.graph.DexValue.DexValueMethodHandle;
import com.debughelper.tools.r8.graph.DexValue.DexValueMethodType;
import com.debughelper.tools.r8.graph.DexValue.DexValueNull;
import com.debughelper.tools.r8.graph.DexValue.DexValueString;
import com.debughelper.tools.r8.DiagnosticsHandler;
import com.debughelper.tools.r8.ProgramResource;
import com.debughelper.tools.r8.code.Instruction;
import com.debughelper.tools.r8.code.InstructionFactory;
import com.debughelper.tools.r8.errors.CompilationError;
import com.debughelper.tools.r8.graph.ClassAccessFlags;
import com.debughelper.tools.r8.graph.ClassKind;
import com.debughelper.tools.r8.graph.Descriptor;
import com.debughelper.tools.r8.graph.DexAnnotation;
import com.debughelper.tools.r8.graph.DexAnnotationElement;
import com.debughelper.tools.r8.graph.DexAnnotationSet;
import com.debughelper.tools.r8.graph.DexCallSite;
import com.debughelper.tools.r8.graph.DexClass;
import com.debughelper.tools.r8.graph.DexDebugEvent;
import com.debughelper.tools.r8.graph.DexDebugInfo;
import com.debughelper.tools.r8.graph.DexEncodedAnnotation;
import com.debughelper.tools.r8.graph.DexEncodedArray;
import com.debughelper.tools.r8.graph.DexEncodedField;
import com.debughelper.tools.r8.graph.DexEncodedMethod;
import com.debughelper.tools.r8.graph.DexField;
import com.debughelper.tools.r8.graph.DexItem;
import com.debughelper.tools.r8.graph.DexItemFactory;
import com.debughelper.tools.r8.graph.DexMethod;
import com.debughelper.tools.r8.graph.DexProto;
import com.debughelper.tools.r8.graph.DexString;
import com.debughelper.tools.r8.graph.DexType;
import com.debughelper.tools.r8.graph.DexTypeList;
import com.debughelper.tools.r8.graph.EnclosingMethodAttribute;
import com.debughelper.tools.r8.graph.FieldAccessFlags;
import com.debughelper.tools.r8.graph.InnerClassAttribute;
import com.debughelper.tools.r8.graph.MethodAccessFlags;
import com.debughelper.tools.r8.graph.OffsetToObjectMapping;
import com.debughelper.tools.r8.graph.ParameterAnnotationsList;
import com.debughelper.tools.r8.logging.Log;
import com.debughelper.tools.r8.origin.Origin;
import com.debughelper.tools.r8.origin.PathOrigin;
import com.debughelper.tools.r8.utils.DefaultDiagnosticsHandler;
import com.debughelper.tools.r8.utils.EncodedValueUtils;
import com.debughelper.tools.r8.utils.Pair;
import com.google.common.io.ByteStreams;
import it.unimi.dsi.fastutil.ints.Int2IntArrayMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ShortBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

public class DexParser {

  private final int NO_INDEX = -1;
  private final com.debughelper.tools.r8.origin.Origin origin;
  private DexReader dexReader;
  private final DexSection[] dexSections;
  private int[] stringIDs;
  private final com.debughelper.tools.r8.graph.ClassKind classKind;
  private final com.debughelper.tools.r8.DiagnosticsHandler reporter;

  public static DexSection[] parseMapFrom(Path file) throws IOException {
    return parseMapFrom(Files.newInputStream(file), new PathOrigin(file));
  }

  public static DexSection[] parseMapFrom(InputStream stream, Origin origin) throws IOException {
    return parseMapFrom(new DexReader(origin, ByteStreams.toByteArray(stream)));
  }

  private static DexSection[] parseMapFrom(DexReader dexReader) {
    DexParser dexParser = new DexParser(dexReader,
        com.debughelper.tools.r8.graph.ClassKind.PROGRAM, new com.debughelper.tools.r8.graph.DexItemFactory(), new DefaultDiagnosticsHandler());
    return dexParser.dexSections;
  }

  public void close() {
    // This close behavior is needed to reduce peak memory usage of D8Adapter/R8.
    indexedItems = null;
    codes = null;
    offsetMap = null;
    dexReader = null;
    stringIDs = null;
  }

  // Mapping from indexes to indexable dex items.
  private com.debughelper.tools.r8.graph.OffsetToObjectMapping indexedItems = new OffsetToObjectMapping();

  // Mapping from offset to code item;
  private Int2ObjectMap<com.debughelper.tools.r8.graph.DexCode> codes = new Int2ObjectOpenHashMap<>();

  // Mapping from offset to dex item;
  private Int2ObjectMap<Object> offsetMap = new Int2ObjectOpenHashMap<>();

  // Factory to canonicalize certain dexitems.
  private final com.debughelper.tools.r8.graph.DexItemFactory dexItemFactory;

  public DexParser(DexReader dexReader,
                   com.debughelper.tools.r8.graph.ClassKind classKind, com.debughelper.tools.r8.graph.DexItemFactory dexItemFactory, DiagnosticsHandler reporter) {
    assert dexReader.getOrigin() != null;
    this.origin = dexReader.getOrigin();
    this.dexReader = dexReader;
    this.dexItemFactory = dexItemFactory;
    dexReader.setByteOrder();
    dexSections = parseMap();
    parseStringIDs();
    this.classKind = classKind;
    this.reporter = reporter;
  }

  private void ensureCodesInited() {
    if (codes == null) {
      codes = new Int2ObjectOpenHashMap<>();
    }

    if (classKind == com.debughelper.tools.r8.graph.ClassKind.LIBRARY) {
      // Ignore contents of library files.
      return;
    }
    DexSection dexSection = lookupSection(Constants.TYPE_CODE_ITEM);
    if (dexSection.length == 0) {
      return;
    }
    dexReader.position(dexSection.offset);
    for (int i = 0; i < dexSection.length; i++) {
      dexReader.align(4);  // code items are 4 byte aligned.
      int offset = dexReader.position();
      com.debughelper.tools.r8.graph.DexCode code = parseCodeItem();
      codes.put(offset, code);  // Update the file local offset to code mapping.
    }
  }

  private com.debughelper.tools.r8.graph.DexTypeList parseTypeList() {
    com.debughelper.tools.r8.graph.DexType[] result = new com.debughelper.tools.r8.graph.DexType[dexReader.getUint()];
    for (int j = 0; j < result.length; j++) {
      result[j] = indexedItems.getType(dexReader.getUshort());
    }
    return new com.debughelper.tools.r8.graph.DexTypeList(result);
  }

  private com.debughelper.tools.r8.graph.DexTypeList typeListAt(int offset) {
    if (offset == 0) {
      return com.debughelper.tools.r8.graph.DexTypeList.empty();
    }
    return (com.debughelper.tools.r8.graph.DexTypeList) cacheAt(offset, this::parseTypeList);
  }

  private com.debughelper.tools.r8.graph.DexValue parseEncodedValue() {
    int header = dexReader.get() & 0xff;
    int valueArg = header >> 5;
    int valueType = header & 0x1f;
    switch (valueType) {
      case com.debughelper.tools.r8.graph.DexValue.VALUE_BYTE: {
        assert valueArg == 0;
        byte value = (byte) EncodedValueUtils.parseSigned(dexReader, 1);
        return com.debughelper.tools.r8.graph.DexValue.DexValueByte.create(value);
      }
      case com.debughelper.tools.r8.graph.DexValue.VALUE_SHORT: {
        int size = valueArg + 1;
        short value = (short) EncodedValueUtils.parseSigned(dexReader, size);
        return com.debughelper.tools.r8.graph.DexValue.DexValueShort.create(value);
      }
      case com.debughelper.tools.r8.graph.DexValue.VALUE_CHAR: {
        int size = valueArg + 1;
        char value = (char) EncodedValueUtils.parseUnsigned(dexReader, size);
        return com.debughelper.tools.r8.graph.DexValue.DexValueChar.create(value);
      }
      case com.debughelper.tools.r8.graph.DexValue.VALUE_INT: {
        int size = valueArg + 1;
        int value = (int) EncodedValueUtils.parseSigned(dexReader, size);
        return com.debughelper.tools.r8.graph.DexValue.DexValueInt.create(value);
      }
      case com.debughelper.tools.r8.graph.DexValue.VALUE_LONG: {
        int size = valueArg + 1;
        long value = EncodedValueUtils.parseSigned(dexReader, size);
        return com.debughelper.tools.r8.graph.DexValue.DexValueLong.create(value);
      }
      case com.debughelper.tools.r8.graph.DexValue.VALUE_FLOAT: {
        int size = valueArg + 1;
        return com.debughelper.tools.r8.graph.DexValue.DexValueFloat.create(EncodedValueUtils.parseFloat(dexReader, size));
      }
      case com.debughelper.tools.r8.graph.DexValue.VALUE_DOUBLE: {
        int size = valueArg + 1;
        return com.debughelper.tools.r8.graph.DexValue.DexValueDouble.create(EncodedValueUtils.parseDouble(dexReader, size));
      }
      case com.debughelper.tools.r8.graph.DexValue.VALUE_STRING: {
        int size = valueArg + 1;
        int index = (int) EncodedValueUtils.parseUnsigned(dexReader, size);
        com.debughelper.tools.r8.graph.DexString value = indexedItems.getString(index);
        return new com.debughelper.tools.r8.graph.DexValue.DexValueString(value);
      }
      case com.debughelper.tools.r8.graph.DexValue.VALUE_TYPE: {
        int size = valueArg + 1;
        com.debughelper.tools.r8.graph.DexType value = indexedItems.getType((int) EncodedValueUtils.parseUnsigned(dexReader, size));
        return new com.debughelper.tools.r8.graph.DexValue.DexValueType(value);
      }
      case com.debughelper.tools.r8.graph.DexValue.VALUE_FIELD: {
        int size = valueArg + 1;
        com.debughelper.tools.r8.graph.DexField value = indexedItems.getField((int) EncodedValueUtils.parseUnsigned(dexReader, size));
        return new com.debughelper.tools.r8.graph.DexValue.DexValueField(value);
      }
      case com.debughelper.tools.r8.graph.DexValue.VALUE_METHOD: {
        int size = valueArg + 1;
        com.debughelper.tools.r8.graph.DexMethod value = indexedItems.getMethod((int) EncodedValueUtils.parseUnsigned(dexReader, size));
        return new com.debughelper.tools.r8.graph.DexValue.DexValueMethod(value);
      }
      case com.debughelper.tools.r8.graph.DexValue.VALUE_ENUM: {
        int size = valueArg + 1;
        com.debughelper.tools.r8.graph.DexField value = indexedItems.getField((int) EncodedValueUtils.parseUnsigned(dexReader, size));
        return new com.debughelper.tools.r8.graph.DexValue.DexValueEnum(value);
      }
      case com.debughelper.tools.r8.graph.DexValue.VALUE_ARRAY: {
        assert valueArg == 0;
        return new com.debughelper.tools.r8.graph.DexValue.DexValueArray(parseEncodedArrayValues());
      }
      case com.debughelper.tools.r8.graph.DexValue.VALUE_ANNOTATION: {
        assert valueArg == 0;
        return new com.debughelper.tools.r8.graph.DexValue.DexValueAnnotation(parseEncodedAnnotation());
      }
      case com.debughelper.tools.r8.graph.DexValue.VALUE_NULL: {
        assert valueArg == 0;
        return com.debughelper.tools.r8.graph.DexValue.DexValueNull.NULL;
      }
      case com.debughelper.tools.r8.graph.DexValue.VALUE_BOOLEAN: {
        // 0 is false, and 1 is true.
        return com.debughelper.tools.r8.graph.DexValue.DexValueBoolean.create(valueArg != 0);
      }
      case com.debughelper.tools.r8.graph.DexValue.VALUE_METHOD_TYPE: {
        int size = valueArg + 1;
        com.debughelper.tools.r8.graph.DexProto value = indexedItems.getProto((int) EncodedValueUtils.parseUnsigned(dexReader, size));
        return new com.debughelper.tools.r8.graph.DexValue.DexValueMethodType(value);
      }
      case com.debughelper.tools.r8.graph.DexValue.VALUE_METHOD_HANDLE: {
        int size = valueArg + 1;
        com.debughelper.tools.r8.graph.DexMethodHandle value = indexedItems.getMethodHandle((int) EncodedValueUtils.parseUnsigned(dexReader, size));
        return new com.debughelper.tools.r8.graph.DexValue.DexValueMethodHandle(value);
      }
      default:
        throw new IndexOutOfBoundsException();
    }
  }

  private com.debughelper.tools.r8.graph.DexEncodedAnnotation parseEncodedAnnotation() {
    int typeIdx = dexReader.getUleb128();
    int size = dexReader.getUleb128();
    com.debughelper.tools.r8.graph.DexAnnotationElement[] elements = new com.debughelper.tools.r8.graph.DexAnnotationElement[size];
    for (int i = 0; i < size; i++) {
      int nameIdx = dexReader.getUleb128();
      com.debughelper.tools.r8.graph.DexValue value = parseEncodedValue();
      elements[i] = new DexAnnotationElement(indexedItems.getString(nameIdx), value);
    }
    return new DexEncodedAnnotation(indexedItems.getType(typeIdx), elements);
  }

  private com.debughelper.tools.r8.graph.DexValue[] parseEncodedArrayValues() {
    int size = dexReader.getUleb128();
    com.debughelper.tools.r8.graph.DexValue[] values = new com.debughelper.tools.r8.graph.DexValue[size];
    for (int i = 0; i < size; i++) {
      values[i] = parseEncodedValue();
    }
    return values;
  }


  private com.debughelper.tools.r8.graph.DexEncodedArray parseEncodedArray() {
    return new com.debughelper.tools.r8.graph.DexEncodedArray(parseEncodedArrayValues());
  }

  private com.debughelper.tools.r8.graph.DexEncodedArray encodedArrayAt(int offset) {
    return (com.debughelper.tools.r8.graph.DexEncodedArray) cacheAt(offset, this::parseEncodedArray);
  }

  private com.debughelper.tools.r8.graph.DexMemberAnnotation.DexFieldAnnotation[] parseFieldAnnotations(int size) {
    if (size == 0) {
      return null;
    }
    int[] fieldIndices = new int[size];
    int[] annotationOffsets = new int[size];
    for (int i = 0; i < size; i++) {
      fieldIndices[i] = dexReader.getUint();
      annotationOffsets[i] = dexReader.getUint();
    }
    int saved = dexReader.position();
    com.debughelper.tools.r8.graph.DexMemberAnnotation.DexFieldAnnotation[] result = new com.debughelper.tools.r8.graph.DexMemberAnnotation.DexFieldAnnotation[size];
    for (int i = 0; i < size; i++) {
      com.debughelper.tools.r8.graph.DexField field = indexedItems.getField(fieldIndices[i]);
      com.debughelper.tools.r8.graph.DexAnnotationSet annotation = annotationSetAt(annotationOffsets[i]);
      result[i] = new com.debughelper.tools.r8.graph.DexMemberAnnotation.DexFieldAnnotation(field, annotation);
    }
    dexReader.position(saved);
    return result;
  }

  private com.debughelper.tools.r8.graph.DexMemberAnnotation.DexMethodAnnotation[] parseMethodAnnotations(int size) {
    if (size == 0) {
      return null;
    }
    int[] methodIndices = new int[size];
    int[] annotationOffsets = new int[size];
    for (int i = 0; i < size; i++) {
      methodIndices[i] = dexReader.getUint();
      annotationOffsets[i] = dexReader.getUint();
    }
    int saved = dexReader.position();
    com.debughelper.tools.r8.graph.DexMemberAnnotation.DexMethodAnnotation[] result = new com.debughelper.tools.r8.graph.DexMemberAnnotation.DexMethodAnnotation[size];
    for (int i = 0; i < size; i++) {
      com.debughelper.tools.r8.graph.DexMethod method = indexedItems.getMethod(methodIndices[i]);
      com.debughelper.tools.r8.graph.DexAnnotationSet annotation = annotationSetAt(annotationOffsets[i]);
      result[i] = new com.debughelper.tools.r8.graph.DexMemberAnnotation.DexMethodAnnotation(method, annotation);
    }
    dexReader.position(saved);
    return result;
  }

  private com.debughelper.tools.r8.graph.ParameterAnnotationsList annotationSetRefListAt(int offset) {
    return (com.debughelper.tools.r8.graph.ParameterAnnotationsList) cacheAt(offset, this::parseAnnotationSetRefList);
  }

  private com.debughelper.tools.r8.graph.ParameterAnnotationsList parseAnnotationSetRefList() {
    int size = dexReader.getUint();
    int[] annotationOffsets = new int[size];
    for (int i = 0; i < size; i++) {
      annotationOffsets[i] = dexReader.getUint();
    }
    com.debughelper.tools.r8.graph.DexAnnotationSet[] values = new com.debughelper.tools.r8.graph.DexAnnotationSet[size];
    for (int i = 0; i < size; i++) {
      values[i] = annotationSetAt(annotationOffsets[i]);
    }
    return new com.debughelper.tools.r8.graph.ParameterAnnotationsList(values);
  }

  private com.debughelper.tools.r8.graph.DexMemberAnnotation.DexParameterAnnotation[] parseParameterAnnotations(int size) {
    if (size == 0) {
      return null;
    }
    int[] methodIndices = new int[size];
    int[] annotationOffsets = new int[size];
    for (int i = 0; i < size; i++) {
      methodIndices[i] = dexReader.getUint();
      annotationOffsets[i] = dexReader.getUint();
    }
    int saved = dexReader.position();
    com.debughelper.tools.r8.graph.DexMemberAnnotation.DexParameterAnnotation[] result = new com.debughelper.tools.r8.graph.DexMemberAnnotation.DexParameterAnnotation[size];
    for (int i = 0; i < size; i++) {
      com.debughelper.tools.r8.graph.DexMethod method = indexedItems.getMethod(methodIndices[i]);
      result[i] = new com.debughelper.tools.r8.graph.DexMemberAnnotation.DexParameterAnnotation(
          method,
          annotationSetRefListAt(annotationOffsets[i])
              .withParameterCount(method.proto.parameters.size()));
    }
    dexReader.position(saved);
    return result;
  }

  private <T> Object cacheAt(int offset, Supplier<T> function, Supplier<T> defaultValue) {
    if (offset == 0) {
      return defaultValue.get();
    }
    return cacheAt(offset, function);
  }

  private <T> Object cacheAt(int offset, Supplier<T> function) {
    if (offset == 0) {
      return null;  // return null for offset zero.
    }
    Object result = offsetMap.get(offset);
    if (result != null) {
      return result;  // return the cached result.
    }
    // Cache is empty so parse the structure.
    dexReader.position(offset);
    result = function.get();
    // Update the map.
    offsetMap.put(offset, result);
    assert offsetMap.get(offset) == result;
    return result;
  }

  private com.debughelper.tools.r8.graph.DexAnnotation parseAnnotation() {
    if (com.debughelper.tools.r8.logging.Log.ENABLED) {
      com.debughelper.tools.r8.logging.Log.verbose(getClass(), "Reading Annotation @ 0x%08x.", dexReader.position());
    }
    int visibility = dexReader.get();
    return new com.debughelper.tools.r8.graph.DexAnnotation(visibility, parseEncodedAnnotation());
  }

  private com.debughelper.tools.r8.graph.DexAnnotation annotationAt(int offset) {
    return (com.debughelper.tools.r8.graph.DexAnnotation) cacheAt(offset, this::parseAnnotation);
  }

  private com.debughelper.tools.r8.graph.DexAnnotationSet parseAnnotationSet() {
    if (com.debughelper.tools.r8.logging.Log.ENABLED) {
      com.debughelper.tools.r8.logging.Log.verbose(getClass(), "Reading AnnotationSet @ 0x%08x.", dexReader.position());
    }
    int size = dexReader.getUint();
    int[] annotationOffsets = new int[size];
    for (int i = 0; i < size; i++) {
      annotationOffsets[i] = dexReader.getUint();
    }
    com.debughelper.tools.r8.graph.DexAnnotation[] result = new com.debughelper.tools.r8.graph.DexAnnotation[size];
    for (int i = 0; i < size; i++) {
      result[i] = annotationAt(annotationOffsets[i]);
    }
    return new com.debughelper.tools.r8.graph.DexAnnotationSet(result);
  }

  private com.debughelper.tools.r8.graph.DexAnnotationSet annotationSetAt(int offset) {
    return (com.debughelper.tools.r8.graph.DexAnnotationSet) cacheAt(offset, this::parseAnnotationSet, com.debughelper.tools.r8.graph.DexAnnotationSet::empty);
  }

  private AnnotationsDirectory annotationsDirectoryAt(int offset) {
    return (AnnotationsDirectory) cacheAt(offset, this::parseAnnotationsDirectory,
        AnnotationsDirectory::empty);
  }

  private AnnotationsDirectory parseAnnotationsDirectory() {
    int classAnnotationsOff = dexReader.getUint();
    int fieldsSize = dexReader.getUint();
    int methodsSize = dexReader.getUint();
    int parametersSize = dexReader.getUint();
    final com.debughelper.tools.r8.graph.DexMemberAnnotation.DexFieldAnnotation[] fields = parseFieldAnnotations(fieldsSize);
    final com.debughelper.tools.r8.graph.DexMemberAnnotation.DexMethodAnnotation[] methods = parseMethodAnnotations(methodsSize);
    final com.debughelper.tools.r8.graph.DexMemberAnnotation.DexParameterAnnotation[] parameters = parseParameterAnnotations(parametersSize);
    return new AnnotationsDirectory(
        annotationSetAt(classAnnotationsOff),
        fields,
        methods,
        parameters);
  }

  private com.debughelper.tools.r8.graph.DexDebugInfo debugInfoAt(int offset) {
    return (com.debughelper.tools.r8.graph.DexDebugInfo) cacheAt(offset, this::parseDebugInfo);
  }

  private com.debughelper.tools.r8.graph.DexDebugInfo parseDebugInfo() {
    int start = dexReader.getUleb128();
    int parametersSize = dexReader.getUleb128();
    com.debughelper.tools.r8.graph.DexString[] parameters = new com.debughelper.tools.r8.graph.DexString[parametersSize];
    for (int i = 0; i < parametersSize; i++) {
      int index = dexReader.getUleb128p1();
      if (index != NO_INDEX) {
        parameters[i] = indexedItems.getString(index);
      }
    }
    List<com.debughelper.tools.r8.graph.DexDebugEvent> events = new ArrayList<>();
    for (int head = dexReader.getUbyte(); head != Constants.DBG_END_SEQUENCE; head = dexReader.getUbyte()) {
      switch (head) {
        case Constants.DBG_ADVANCE_PC:
          events.add(dexItemFactory.createAdvancePC(dexReader.getUleb128()));
          break;
        case Constants.DBG_ADVANCE_LINE:
          events.add(dexItemFactory.createAdvanceLine(dexReader.getSleb128()));
          break;
        case Constants.DBG_START_LOCAL: {
          int registerNum = dexReader.getUleb128();
          int nameIdx = dexReader.getUleb128p1();
          int typeIdx = dexReader.getUleb128p1();
          events.add(new com.debughelper.tools.r8.graph.DexDebugEvent.StartLocal(
              registerNum,
              nameIdx == NO_INDEX ? null : indexedItems.getString(nameIdx),
              typeIdx == NO_INDEX ? null : indexedItems.getType(typeIdx),
              null));
          break;
        }
        case Constants.DBG_START_LOCAL_EXTENDED: {
          int registerNum = dexReader.getUleb128();
          int nameIdx = dexReader.getUleb128p1();
          int typeIdx = dexReader.getUleb128p1();
          int sigIdx = dexReader.getUleb128p1();
          events.add(new com.debughelper.tools.r8.graph.DexDebugEvent.StartLocal(
              registerNum,
              nameIdx == NO_INDEX ? null : indexedItems.getString(nameIdx),
              typeIdx == NO_INDEX ? null : indexedItems.getType(typeIdx),
              sigIdx == NO_INDEX ? null : indexedItems.getString(sigIdx)));
          break;
        }
        case Constants.DBG_END_LOCAL: {
          events.add(dexItemFactory.createEndLocal(dexReader.getUleb128()));
          break;
        }
        case Constants.DBG_RESTART_LOCAL: {
          events.add(dexItemFactory.createRestartLocal(dexReader.getUleb128()));
          break;
        }
        case Constants.DBG_SET_PROLOGUE_END: {
          events.add(dexItemFactory.createSetPrologueEnd());
          break;
        }
        case Constants.DBG_SET_EPILOGUE_BEGIN: {
          events.add(dexItemFactory.createSetEpilogueBegin());
          break;
        }
        case Constants.DBG_SET_FILE: {
          int nameIdx = dexReader.getUleb128p1();
          com.debughelper.tools.r8.graph.DexString sourceFile = nameIdx == NO_INDEX ? null : indexedItems.getString(nameIdx);
          events.add(dexItemFactory.createSetFile(sourceFile));
          break;
        }
        default: {
          assert head >= 0x0a && head <= 0xff;
          events.add(dexItemFactory.createDefault(head));
        }
      }
    }
    return new com.debughelper.tools.r8.graph.DexDebugInfo(start, parameters, events.toArray(new DexDebugEvent[events.size()]));
  }

  private static class MemberAnnotationIterator<S extends com.debughelper.tools.r8.graph.Descriptor<?, S>, T extends com.debughelper.tools.r8.graph.DexItem> {

    private int index = 0;
    private final com.debughelper.tools.r8.graph.DexMemberAnnotation<S, T>[] annotations;
    private final Supplier<T> emptyValue;

    private MemberAnnotationIterator(com.debughelper.tools.r8.graph.DexMemberAnnotation<S, T>[] annotations,
                                     Supplier<T> emptyValue) {
      this.annotations = annotations;
      this.emptyValue = emptyValue;
    }

    // Get the annotation set for an item. This method assumes that it is always called with
    // an item that is higher in the sorting order than the last item.
    T getNextFor(S item) {
      // TODO(ager): We could use the indices from the file to make this search faster using
      // compareTo instead of slowCompareTo. That would require us to assign indices during
      // reading. Those indices should be cleared after reading to make sure that we resort
      // everything correctly at the end.
      while (index < annotations.length && annotations[index].item.slowCompareTo(item) < 0) {
        index++;
      }
      if (index >= annotations.length || !annotations[index].item.equals(item)) {
        return emptyValue.get();
      }
      return annotations[index].annotations;
    }
  }

  private com.debughelper.tools.r8.graph.DexEncodedField[] readFields(int size, com.debughelper.tools.r8.graph.DexMemberAnnotation.DexFieldAnnotation[] annotations,
                                                                      com.debughelper.tools.r8.graph.DexValue[] staticValues) {
    com.debughelper.tools.r8.graph.DexEncodedField[] fields = new com.debughelper.tools.r8.graph.DexEncodedField[size];
    int fieldIndex = 0;
    MemberAnnotationIterator<com.debughelper.tools.r8.graph.DexField, com.debughelper.tools.r8.graph.DexAnnotationSet> annotationIterator =
        new MemberAnnotationIterator<>(annotations, com.debughelper.tools.r8.graph.DexAnnotationSet::empty);
    for (int i = 0; i < size; i++) {
      fieldIndex += dexReader.getUleb128();
      com.debughelper.tools.r8.graph.DexField field = indexedItems.getField(fieldIndex);
      com.debughelper.tools.r8.graph.FieldAccessFlags accessFlags = FieldAccessFlags.fromDexAccessFlags(dexReader.getUleb128());
      com.debughelper.tools.r8.graph.DexAnnotationSet fieldAnnotations = annotationIterator.getNextFor(field);
      com.debughelper.tools.r8.graph.DexValue staticValue = null;
      if (accessFlags.isStatic()) {
        if (staticValues != null && i < staticValues.length) {
          staticValue = staticValues[i];
        }
      }
      fields[i] = new com.debughelper.tools.r8.graph.DexEncodedField(field, accessFlags, fieldAnnotations, staticValue);
    }
    return fields;
  }

  private com.debughelper.tools.r8.graph.DexEncodedMethod[] readMethods(int size, com.debughelper.tools.r8.graph.DexMemberAnnotation.DexMethodAnnotation[] annotations,
                                                                        com.debughelper.tools.r8.graph.DexMemberAnnotation.DexParameterAnnotation[] parameters, boolean skipCodes) {
    com.debughelper.tools.r8.graph.DexEncodedMethod[] methods = new com.debughelper.tools.r8.graph.DexEncodedMethod[size];
    int methodIndex = 0;
    MemberAnnotationIterator<com.debughelper.tools.r8.graph.DexMethod, com.debughelper.tools.r8.graph.DexAnnotationSet> annotationIterator =
        new MemberAnnotationIterator<>(annotations, com.debughelper.tools.r8.graph.DexAnnotationSet::empty);
    MemberAnnotationIterator<com.debughelper.tools.r8.graph.DexMethod, com.debughelper.tools.r8.graph.ParameterAnnotationsList> parameterAnnotationsIterator =
        new MemberAnnotationIterator<>(parameters, ParameterAnnotationsList::empty);
    for (int i = 0; i < size; i++) {
      methodIndex += dexReader.getUleb128();
      com.debughelper.tools.r8.graph.MethodAccessFlags accessFlags = MethodAccessFlags.fromDexAccessFlags(dexReader.getUleb128());
      int codeOff = dexReader.getUleb128();
      com.debughelper.tools.r8.graph.DexCode code = null;
      if (!skipCodes) {
        assert codeOff == 0 || codes.get(codeOff) != null;
        code = codes.get(codeOff);
      }
      com.debughelper.tools.r8.graph.DexMethod method = indexedItems.getMethod(methodIndex);
      methods[i] = new com.debughelper.tools.r8.graph.DexEncodedMethod(method, accessFlags, annotationIterator.getNextFor(method),
          parameterAnnotationsIterator.getNextFor(method), code);
    }
    return methods;
  }

  void addClassDefsTo(Consumer<com.debughelper.tools.r8.graph.DexClass> classCollection) {
    ensureCodesInited();
    final DexSection dexSection = lookupSection(Constants.TYPE_CLASS_DEF_ITEM);
    final int length = dexSection.length;
    indexedItems.initializeClasses(length);
    if (length == 0) {
      return;
    }
    dexReader.position(dexSection.offset);

    int[] classIndices = new int[length];
    int[] accessFlags = new int[length];
    int[] superclassIndices = new int[length];
    int[] interfacesOffsets = new int[length];
    int[] sourceFileIndices = new int[length];
    int[] annotationsOffsets = new int[length];
    int[] classDataOffsets = new int[length];
    int[] staticValuesOffsets = new int[length];

    for (int i = 0; i < length; i++) {
      if (com.debughelper.tools.r8.logging.Log.ENABLED) {
        com.debughelper.tools.r8.logging.Log.verbose(getClass(), "Reading ClassDef @ 0x%08x.", dexReader.position());
      }
      classIndices[i] = dexReader.getUint();
      accessFlags[i] = dexReader.getUint();
      superclassIndices[i] = dexReader.getInt();
      interfacesOffsets[i] = dexReader.getUint();
      sourceFileIndices[i] = dexReader.getInt();
      annotationsOffsets[i] = dexReader.getUint();
      classDataOffsets[i] = dexReader.getUint();
      staticValuesOffsets[i] = dexReader.getUint();
    }

    for (int i = 0; i < length; i++) {
      int superclassIdx = superclassIndices[i];
      com.debughelper.tools.r8.graph.DexType superclass = superclassIdx == NO_INDEX ? null : indexedItems.getType(superclassIdx);
      int srcIdx = sourceFileIndices[i];
      com.debughelper.tools.r8.graph.DexString source = srcIdx == NO_INDEX ? null : indexedItems.getString(srcIdx);
      com.debughelper.tools.r8.graph.DexType type = indexedItems.getType(classIndices[i]);
      com.debughelper.tools.r8.graph.ClassAccessFlags flags = ClassAccessFlags.fromDexAccessFlags(accessFlags[i]);
      // Check if constraints from
      // https://docs.oracle.com/javase/specs/jvms/se7/html/jvms-4.html#jvms-4.1 are met.
      if (!flags.areValid(Constants.CORRESPONDING_CLASS_FILE_VERSION, false)) {
        throw new CompilationError("Class " + type.toSourceString()
            + " has illegal access flags. Found: " + flags, origin);
      }
      com.debughelper.tools.r8.graph.DexEncodedField[] staticFields = com.debughelper.tools.r8.graph.DexEncodedField.EMPTY_ARRAY;
      com.debughelper.tools.r8.graph.DexEncodedField[] instanceFields = DexEncodedField.EMPTY_ARRAY;
      com.debughelper.tools.r8.graph.DexEncodedMethod[] directMethods = com.debughelper.tools.r8.graph.DexEncodedMethod.EMPTY_ARRAY;
      com.debughelper.tools.r8.graph.DexEncodedMethod[] virtualMethods = DexEncodedMethod.EMPTY_ARRAY;
      AnnotationsDirectory annotationsDirectory = annotationsDirectoryAt(annotationsOffsets[i]);
      if (classDataOffsets[i] != 0) {
        com.debughelper.tools.r8.graph.DexEncodedArray staticValues = encodedArrayAt(staticValuesOffsets[i]);

        dexReader.position(classDataOffsets[i]);
        int staticFieldsSize = dexReader.getUleb128();
        int instanceFieldsSize = dexReader.getUleb128();
        int directMethodsSize = dexReader.getUleb128();
        int virtualMethodsSize = dexReader.getUleb128();

        staticFields = readFields(staticFieldsSize, annotationsDirectory.fields,
            staticValues != null ? staticValues.values : null);
        instanceFields = readFields(instanceFieldsSize, annotationsDirectory.fields, null);
        directMethods =
            readMethods(
                directMethodsSize,
                annotationsDirectory.methods,
                annotationsDirectory.parameters,
                classKind != com.debughelper.tools.r8.graph.ClassKind.PROGRAM);
        virtualMethods =
            readMethods(
                virtualMethodsSize,
                annotationsDirectory.methods,
                annotationsDirectory.parameters,
                classKind != ClassKind.PROGRAM);
      }

      AttributesAndAnnotations attrs =
          new AttributesAndAnnotations(type, annotationsDirectory.clazz, dexItemFactory);

      DexClass clazz =
          classKind.create(
              type,
              ProgramResource.Kind.DEX,
              origin,
              flags,
              superclass,
              typeListAt(interfacesOffsets[i]),
              source,
              attrs.getEnclosingMethodAttribute(),
              attrs.getInnerClasses(),
              attrs.getAnnotations(),
              staticFields,
              instanceFields,
              directMethods,
              virtualMethods,
              dexItemFactory.getSkipNameValidationForTesting());
      classCollection.accept(clazz);  // Update the application object.
    }
  }

  private void parseStringIDs() {
    DexSection dexSection = lookupSection(Constants.TYPE_STRING_ID_ITEM);
    stringIDs = new int[dexSection.length];
    if (dexSection.length == 0) {
      return;
    }
    dexReader.position(dexSection.offset);
    for (int i = 0; i < dexSection.length; i++) {
      stringIDs[i] = dexReader.getUint();
    }
  }

  private DexSection lookupSection(int type) {
    for (DexSection s : dexSections) {
      if (s.type == type) {
        return s;
      }
    }
    // If the section doesn't exist, return an empty section of this type.
    return new DexSection(type, 0, 0, 0);
  }

  private DexSection[] parseMap() {
    // Read the dexSections information from the MAP.
    int mapOffset = dexReader.getUint(Constants.MAP_OFF_OFFSET);
    dexReader.position(mapOffset);
    int mapSize = dexReader.getUint();
    final DexSection[] result = new DexSection[mapSize];
    for (int i = 0; i < mapSize; i++) {
      int type = dexReader.getUshort();
      int unused = dexReader.getUshort();
      int size = dexReader.getUint();
      int offset = dexReader.getUint();
      result[i] = new DexSection(type, unused, size, offset);
    }
    if (com.debughelper.tools.r8.logging.Log.ENABLED) {
      for (int i = 0; i < result.length; i++) {
        DexSection dexSection = result[i];
        int nextOffset = i < result.length - 1 ? result[i + 1].offset : dexSection.offset;
        Log.debug(this.getClass(), "Read section 0x%04x @ 0x%08x #items %08d size 0x%08x.",
            dexSection.type, dexSection.offset, dexSection.length, nextOffset - dexSection.offset);
      }
    }
    for (int i = 0; i < mapSize - 1; i++) {
      result[i].setEnd(result[i + 1].offset);
    }
    result[mapSize - 1].setEnd(dexReader.end());
    return result;
  }

  private com.debughelper.tools.r8.graph.DexCode parseCodeItem() {
    int registerSize = dexReader.getUshort();
    int insSize = dexReader.getUshort();
    int outsSize = dexReader.getUshort();
    int triesSize = dexReader.getUshort();
    int debugInfoOff = dexReader.getUint();
    int insnsSize = dexReader.getUint();
    short[] code = new short[insnsSize];
    com.debughelper.tools.r8.graph.DexCode.Try[] tries = new com.debughelper.tools.r8.graph.DexCode.Try[triesSize];
    com.debughelper.tools.r8.graph.DexCode.TryHandler[] handlers = null;

    if (insnsSize != 0) {
      for (int i = 0; i < insnsSize; i++) {
        code[i] = dexReader.getShort();
      }
      if (insnsSize % 2 != 0) {
        dexReader.getUshort();  // Skip padding ushort
      }
      if (triesSize > 0) {
        Int2IntArrayMap handlerMap = new Int2IntArrayMap();
        // tries: try_item[tries_size].
        for (int i = 0; i < triesSize; i++) {
          int startAddr = dexReader.getUint();
          int insnCount = dexReader.getUshort();
          int handlerOff = dexReader.getUshort();
          tries[i] = new com.debughelper.tools.r8.graph.DexCode.Try(startAddr, insnCount, handlerOff);
        }
        // handlers: encoded_catch_handler_list
        int encodedCatchHandlerListPosition = dexReader.position();
        // - size: uleb128
        int size = dexReader.getUleb128();
        handlers = new com.debughelper.tools.r8.graph.DexCode.TryHandler[size];
        // - list: encoded_catch_handler[handlers_size]
        for (int i = 0; i < size; i++) {
          // encoded_catch_handler
          int encodedCatchHandlerOffset = dexReader.position() - encodedCatchHandlerListPosition;
          handlerMap.put(encodedCatchHandlerOffset, i);
          // - size:	sleb128
          int hsize = dexReader.getSleb128();
          int realHsize = Math.abs(hsize);
          // - handlers	encoded_type_addr_pair[abs(size)]
          com.debughelper.tools.r8.graph.DexCode.TryHandler.TypeAddrPair pairs[] = new com.debughelper.tools.r8.graph.DexCode.TryHandler.TypeAddrPair[realHsize];
          for (int j = 0; j < realHsize; j++) {
            int typeIdx = dexReader.getUleb128();
            int addr = dexReader.getUleb128();
            pairs[j] = new com.debughelper.tools.r8.graph.DexCode.TryHandler.TypeAddrPair(indexedItems.getType(typeIdx), addr);
          }
          int catchAllAddr = -1;
          if (hsize <= 0) {
            catchAllAddr = dexReader.getUleb128();
          }
          handlers[i] = new com.debughelper.tools.r8.graph.DexCode.TryHandler(pairs, catchAllAddr);
        }
        // Convert the handler offsets inside the Try objects to indexes.
        for (com.debughelper.tools.r8.graph.DexCode.Try t : tries) {
          t.setHandlerIndex(handlerMap);
        }
      }
    }
    // Store and restore offset information around reading debug info.
    int saved = dexReader.position();
    DexDebugInfo debugInfo = debugInfoAt(debugInfoOff);
    dexReader.position(saved);
    com.debughelper.tools.r8.code.InstructionFactory factory = new InstructionFactory();
    Instruction[] instructions =
        factory.readSequenceFrom(ShortBuffer.wrap(code), 0, code.length, indexedItems);
    return new com.debughelper.tools.r8.graph.DexCode(
        registerSize,
        insSize,
        outsSize,
        instructions,
        tries,
        handlers,
        debugInfo,
        factory.getHighestSortingString());
  }

  void populateIndexTables() {
    // Populate structures that are already sorted upon read.
    populateStrings();  // Depends on nothing.
    populateTypes();  // Depends on Strings.
    populateFields();  // Depends on Types, and Strings.
    populateProtos();  // Depends on Types and Strings.
    populateMethods();  // Depends on Protos, Types, and Strings.
    populateMethodHandles(); // Depends on Methods and Fields
    populateCallSites(); // Depends on MethodHandles
  }

  private void populateStrings() {
    indexedItems.initializeStrings(stringIDs.length);
    for (int i = 0; i < stringIDs.length; i++) {
      indexedItems.setString(i, stringAt(i));
    }
  }

  private void populateMethodHandles() {
    DexSection dexSection = lookupSection(Constants.TYPE_METHOD_HANDLE_ITEM);
    indexedItems.initializeMethodHandles(dexSection.length);
    for (int i = 0; i < dexSection.length; i++) {
      indexedItems.setMethodHandle(i, methodHandleAt(i));
    }
  }

  private void populateCallSites() {
    DexSection dexSection = lookupSection(Constants.TYPE_CALL_SITE_ID_ITEM);
    indexedItems.initializeCallSites(dexSection.length);
    for (int i = 0; i < dexSection.length; i++) {
      indexedItems.setCallSites(i, callSiteAt(i));
    }
  }

  private void populateTypes() {
    DexSection dexSection = lookupSection(Constants.TYPE_TYPE_ID_ITEM);
    indexedItems.initializeTypes(dexSection.length);
    for (int i = 0; i < dexSection.length; i++) {
      indexedItems.setType(i, typeAt(i));
    }
  }

  private void populateFields() {
    DexSection dexSection = lookupSection(Constants.TYPE_FIELD_ID_ITEM);
    indexedItems.initializeFields(dexSection.length);
    for (int i = 0; i < dexSection.length; i++) {
      indexedItems.setField(i, fieldAt(i));
    }
  }

  private void populateProtos() {
    DexSection dexSection = lookupSection(Constants.TYPE_PROTO_ID_ITEM);
    indexedItems.initializeProtos(dexSection.length);
    for (int i = 0; i < dexSection.length; i++) {
      indexedItems.setProto(i, protoAt(i));
    }
  }

  private void populateMethods() {
    DexSection dexSection = lookupSection(Constants.TYPE_METHOD_ID_ITEM);
    indexedItems.initializeMethods(dexSection.length);
    for (int i = 0; i < dexSection.length; i++) {
      indexedItems.setMethod(i, methodAt(i));
    }
  }

  private com.debughelper.tools.r8.graph.DexString stringAt(int index) {
    final int offset = stringIDs[index];
    dexReader.position(offset);
    int size = dexReader.getUleb128();
    ByteArrayOutputStream os = new ByteArrayOutputStream();
    byte read;
    do {
      read = dexReader.get();
      os.write(read);
    } while (read != 0);
    return dexItemFactory.createString(size, os.toByteArray());
  }

  private com.debughelper.tools.r8.graph.DexType typeAt(int index) {
    DexSection dexSection = lookupSection(Constants.TYPE_TYPE_ID_ITEM);
    if (index >= dexSection.length) {
      return null;
    }
    int offset = dexSection.offset + (Constants.TYPE_TYPE_ID_ITEM_SIZE * index);
    int stringIndex = dexReader.getUint(offset);
    return dexItemFactory.createType(indexedItems.getString(stringIndex));
  }

  private DexField fieldAt(int index) {
    DexSection dexSection = lookupSection(Constants.TYPE_FIELD_ID_ITEM);
    if (index >= dexSection.length) {
      return null;
    }
    int offset = dexSection.offset + (Constants.TYPE_FIELD_ID_ITEM_SIZE * index);
    dexReader.position(offset);
    int classIndex = dexReader.getUshort();
    int typeIndex = dexReader.getUshort();
    int nameIndex = dexReader.getUint();
    com.debughelper.tools.r8.graph.DexType clazz = indexedItems.getType(classIndex);
    com.debughelper.tools.r8.graph.DexType type = indexedItems.getType(typeIndex);
    com.debughelper.tools.r8.graph.DexString name = indexedItems.getString(nameIndex);
    return dexItemFactory.createField(clazz, type, name);
  }

  private com.debughelper.tools.r8.graph.DexMethodHandle methodHandleAt(int index) {
    DexSection dexSection = lookupSection(Constants.TYPE_METHOD_HANDLE_ITEM);
    if (index >= dexSection.length) {
      return null;
    }
    int offset = dexSection.offset + (Constants.TYPE_METHOD_HANDLE_ITEM_SIZE * index);
    dexReader.position(offset);
    com.debughelper.tools.r8.graph.DexMethodHandle.MethodHandleType type = com.debughelper.tools.r8.graph.DexMethodHandle.MethodHandleType.getKind(dexReader.getUshort());
    dexReader.getUshort(); // unused
    int indexFieldOrMethod = dexReader.getUshort();
    com.debughelper.tools.r8.graph.Descriptor<? extends DexItem, ? extends Descriptor<?, ?>> fieldOrMethod;
    switch (type) {
      case INSTANCE_GET:
      case INSTANCE_PUT:
      case STATIC_GET:
      case STATIC_PUT: {
        fieldOrMethod = indexedItems.getField(indexFieldOrMethod);
        break;
      }
      case INVOKE_CONSTRUCTOR:
      case INVOKE_DIRECT:
      case INVOKE_INTERFACE:
      case INVOKE_INSTANCE:
      case INVOKE_STATIC: {
        fieldOrMethod = indexedItems.getMethod(indexFieldOrMethod);
        break;
      }
      default:
        throw new AssertionError("Method handle type unsupported in a dex file.");
    }
    dexReader.getUshort(); // unused

    return dexItemFactory.createMethodHandle(type, fieldOrMethod);
  }

  private DexCallSite callSiteAt(int index) {
    DexSection dexSection = lookupSection(Constants.TYPE_CALL_SITE_ID_ITEM);
    if (index >= dexSection.length) {
      return null;
    }
    int callSiteOffset =
        dexReader.getUint(dexSection.offset + (Constants.TYPE_CALL_SITE_ID_ITEM_SIZE * index));
    DexEncodedArray callSiteEncodedArray = encodedArrayAt(callSiteOffset);
    com.debughelper.tools.r8.graph.DexValue[] values = callSiteEncodedArray.values;
    assert values[0] instanceof com.debughelper.tools.r8.graph.DexValue.DexValueMethodHandle;
    assert values[1] instanceof com.debughelper.tools.r8.graph.DexValue.DexValueString;
    assert values[2] instanceof com.debughelper.tools.r8.graph.DexValue.DexValueMethodType;

    return dexItemFactory.createCallSite(
        ((com.debughelper.tools.r8.graph.DexValue.DexValueString) values[1]).value,
        ((com.debughelper.tools.r8.graph.DexValue.DexValueMethodType) values[2]).value,
        ((com.debughelper.tools.r8.graph.DexValue.DexValueMethodHandle) values[0]).value,
        // 3 means first extra args
        Arrays.asList(Arrays.copyOfRange(values, 3, values.length)));
  }

  private DexProto protoAt(int index) {
    DexSection dexSection = lookupSection(Constants.TYPE_PROTO_ID_ITEM);
    if (index >= dexSection.length) {
      return null;
    }
    int offset = dexSection.offset + (Constants.TYPE_PROTO_ID_ITEM_SIZE * index);
    dexReader.position(offset);
    int shortyIndex = dexReader.getUint();
    int returnTypeIndex = dexReader.getUint();
    int parametersOffsetIndex = dexReader.getUint();
    com.debughelper.tools.r8.graph.DexString shorty = indexedItems.getString(shortyIndex);
    com.debughelper.tools.r8.graph.DexType returnType = indexedItems.getType(returnTypeIndex);
    DexTypeList parameters = typeListAt(parametersOffsetIndex);
    return dexItemFactory.createProto(returnType, shorty, parameters);
  }

  private com.debughelper.tools.r8.graph.DexMethod methodAt(int index) {
    DexSection dexSection = lookupSection(Constants.TYPE_METHOD_ID_ITEM);
    if (index >= dexSection.length) {
      return null;
    }
    int offset = dexSection.offset + (Constants.TYPE_METHOD_ID_ITEM_SIZE * index);
    dexReader.position(offset);
    int classIndex = dexReader.getUshort();
    int protoIndex = dexReader.getUshort();
    int nameIndex = dexReader.getUint();
    return dexItemFactory.createMethod(
        indexedItems.getType(classIndex),
        indexedItems.getProto(protoIndex),
        indexedItems.getString(nameIndex));
  }

  private static class AnnotationsDirectory {

    private static final com.debughelper.tools.r8.graph.DexMemberAnnotation.DexParameterAnnotation[] NO_PARAMETER_ANNOTATIONS =
        new com.debughelper.tools.r8.graph.DexMemberAnnotation.DexParameterAnnotation[0];

    private static final com.debughelper.tools.r8.graph.DexMemberAnnotation.DexFieldAnnotation[] NO_FIELD_ANNOTATIONS =
        new com.debughelper.tools.r8.graph.DexMemberAnnotation.DexFieldAnnotation[0];

    private static final com.debughelper.tools.r8.graph.DexMemberAnnotation.DexMethodAnnotation[] NO_METHOD_ANNOTATIONS =
        new com.debughelper.tools.r8.graph.DexMemberAnnotation.DexMethodAnnotation[0];

    private static final AnnotationsDirectory THE_EMPTY_ANNOTATIONS_DIRECTORY =
        new AnnotationsDirectory(com.debughelper.tools.r8.graph.DexAnnotationSet.empty(),
            NO_FIELD_ANNOTATIONS, new com.debughelper.tools.r8.graph.DexMemberAnnotation.DexMethodAnnotation[0],
            NO_PARAMETER_ANNOTATIONS);

    public final com.debughelper.tools.r8.graph.DexAnnotationSet clazz;
    public final com.debughelper.tools.r8.graph.DexMemberAnnotation.DexFieldAnnotation[] fields;
    public final com.debughelper.tools.r8.graph.DexMemberAnnotation.DexMethodAnnotation[] methods;
    public final com.debughelper.tools.r8.graph.DexMemberAnnotation.DexParameterAnnotation[] parameters;

    AnnotationsDirectory(com.debughelper.tools.r8.graph.DexAnnotationSet clazz,
                         com.debughelper.tools.r8.graph.DexMemberAnnotation.DexFieldAnnotation[] fields,
                         com.debughelper.tools.r8.graph.DexMemberAnnotation.DexMethodAnnotation[] methods,
                         com.debughelper.tools.r8.graph.DexMemberAnnotation.DexParameterAnnotation[] parameters) {
      this.clazz = clazz == null ? com.debughelper.tools.r8.graph.DexAnnotationSet.empty() : clazz;
      this.fields = fields == null ? NO_FIELD_ANNOTATIONS : fields;
      this.methods = methods == null ? NO_METHOD_ANNOTATIONS : methods;
      this.parameters = parameters == null ? NO_PARAMETER_ANNOTATIONS : parameters;
    }

    public static AnnotationsDirectory empty() {
      return THE_EMPTY_ANNOTATIONS_DIRECTORY;
    }
  }

  private static class AttributesAndAnnotations {

    private final com.debughelper.tools.r8.graph.DexAnnotationSet originalAnnotations;
    private com.debughelper.tools.r8.graph.EnclosingMethodAttribute enclosingMethodAttribute = null;
    private List<com.debughelper.tools.r8.graph.InnerClassAttribute> innerClasses = null;
    private List<com.debughelper.tools.r8.graph.DexAnnotation> lazyAnnotations = null;

    public com.debughelper.tools.r8.graph.DexAnnotationSet getAnnotations() {
      if (lazyAnnotations != null) {
        int size = lazyAnnotations.size();
        return size == 0
            ? com.debughelper.tools.r8.graph.DexAnnotationSet.empty()
            : new com.debughelper.tools.r8.graph.DexAnnotationSet(lazyAnnotations.toArray(new com.debughelper.tools.r8.graph.DexAnnotation[size]));
      }
      return originalAnnotations;
    }

    public List<com.debughelper.tools.r8.graph.InnerClassAttribute> getInnerClasses() {
      return innerClasses == null ? Collections.emptyList() : innerClasses;
    }

    public com.debughelper.tools.r8.graph.EnclosingMethodAttribute getEnclosingMethodAttribute() {
      return enclosingMethodAttribute;
    }

    public AttributesAndAnnotations(
            com.debughelper.tools.r8.graph.DexType type, DexAnnotationSet annotations, DexItemFactory factory) {
      this.originalAnnotations = annotations;
      com.debughelper.tools.r8.graph.DexType enclosingClass = null;
      DexMethod enclosingMethod = null;
      List<com.debughelper.tools.r8.graph.DexType> memberClasses = null;

      for (int i = 0; i < annotations.annotations.length; i++) {
        com.debughelper.tools.r8.graph.DexAnnotation annotation = annotations.annotations[i];
        if (com.debughelper.tools.r8.graph.DexAnnotation.isEnclosingClassAnnotation(annotation, factory)) {
          ensureAnnotations(i);
          enclosingClass = com.debughelper.tools.r8.graph.DexAnnotation.getEnclosingClassFromAnnotation(annotation, factory);
        } else if (com.debughelper.tools.r8.graph.DexAnnotation.isEnclosingMethodAnnotation(annotation, factory)) {
          ensureAnnotations(i);
          enclosingMethod = com.debughelper.tools.r8.graph.DexAnnotation.getEnclosingMethodFromAnnotation(annotation, factory);
        } else if (com.debughelper.tools.r8.graph.DexAnnotation.isInnerClassAnnotation(annotation, factory)) {
          ensureAnnotations(i);
          if (innerClasses == null) {
            innerClasses = new ArrayList<>(annotations.annotations.length - i);
          }
          Pair<DexString, Integer> entry =
              com.debughelper.tools.r8.graph.DexAnnotation.getInnerClassFromAnnotation(annotation, factory);
          innerClasses.add(
              new com.debughelper.tools.r8.graph.InnerClassAttribute(entry.getSecond(), type, null, entry.getFirst()));
        } else if (com.debughelper.tools.r8.graph.DexAnnotation.isMemberClassesAnnotation(annotation, factory)) {
          ensureAnnotations(i);
          List<com.debughelper.tools.r8.graph.DexType> members = com.debughelper.tools.r8.graph.DexAnnotation.getMemberClassesFromAnnotation(annotation, factory);
          if (memberClasses == null) {
            memberClasses = members;
          } else {
            memberClasses.addAll(members);
          }
        } else {
          copyAnnotation(annotation);
        }
      }

      if (enclosingClass != null || enclosingMethod != null) {
        assert enclosingClass == null || enclosingMethod == null;
        if (enclosingMethod != null) {
          enclosingMethodAttribute = new com.debughelper.tools.r8.graph.EnclosingMethodAttribute(enclosingMethod);
        } else {
          com.debughelper.tools.r8.graph.InnerClassAttribute namedEnclosing = null;
          if (innerClasses != null) {
            for (com.debughelper.tools.r8.graph.InnerClassAttribute innerClass : innerClasses) {
              if (type == innerClass.getInner()) {
                // If inner-class is anonymous then we create an enclosing-method attribute.
                // Unfortunately we can't distinguish member classes from local classes, and thus
                // can't at this point conform to the spec which requires a enclosing-method
                // attribute iff the inner-class is anonymous or local. A local inner class will
                // thus be represented as an ordinary member class and given an inner-classes
                // entry below.
                namedEnclosing = innerClass.isNamed() ? innerClass : null;
                break;
              }
            }
          }
          if (namedEnclosing == null) {
            enclosingMethodAttribute = new EnclosingMethodAttribute(enclosingClass);
          } else {
            assert innerClasses != null;
            innerClasses.remove(namedEnclosing);
            innerClasses.add(
                new com.debughelper.tools.r8.graph.InnerClassAttribute(
                    namedEnclosing.getAccess(),
                    type,
                    enclosingClass,
                    namedEnclosing.getInnerName()));
          }
        }
      }

      if (memberClasses != null) {
        if (innerClasses == null) {
          innerClasses = new ArrayList<>(memberClasses.size());
        }
        for (DexType memberClass : memberClasses) {
          innerClasses.add(InnerClassAttribute.createUnknownNamedInnerClass(memberClass, type));
        }
      }
    }

    private void ensureAnnotations(int index) {
      if (lazyAnnotations == null) {
        lazyAnnotations = new ArrayList<>(originalAnnotations.annotations.length);
        lazyAnnotations.addAll(Arrays.asList(originalAnnotations.annotations).subList(0, index));
      }
    }

    private void copyAnnotation(DexAnnotation annotation) {
      if (lazyAnnotations != null) {
        lazyAnnotations.add(annotation);
      }
    }
  }
}
