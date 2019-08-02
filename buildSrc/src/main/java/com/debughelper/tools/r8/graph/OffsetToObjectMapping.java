// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.debughelper.tools.r8.graph;

import com.debughelper.tools.r8.graph.DexProgramClass;
import com.debughelper.tools.r8.graph.DexProto;
import com.debughelper.tools.r8.graph.DexType;

public class OffsetToObjectMapping {

  private com.debughelper.tools.r8.graph.DexProgramClass[] classes;
  private DexMethod[] methods;
  private com.debughelper.tools.r8.graph.DexProto[] protos;
  private DexField[] fields;
  private com.debughelper.tools.r8.graph.DexType[] types;
  private DexString[] strings;
  private DexCallSite[] callSites;
  private DexMethodHandle[] methodHandles;

  public void initializeClasses(int length) {
    assert classes == null;
    classes = new com.debughelper.tools.r8.graph.DexProgramClass[length];
  }

  public void initializeMethods(int length) {
    assert methods == null;
    methods = new DexMethod[length];
  }

  public void initializeProtos(int length) {
    assert protos == null;
    protos = new com.debughelper.tools.r8.graph.DexProto[length];
  }

  public void initializeFields(int length) {
    assert fields == null;
    fields = new DexField[length];
  }

  public void initializeTypes(int length) {
    assert types == null;
    types = new com.debughelper.tools.r8.graph.DexType[length];
  }

  public void initializeStrings(int length) {
    assert strings == null;
    strings = new DexString[length];
  }

  public void initializeCallSites(int length) {
    assert callSites == null;
    callSites = new DexCallSite[length];
  }

  public void initializeMethodHandles(int length) {
    assert methodHandles == null;
    methodHandles = new DexMethodHandle[length];
  }

  public com.debughelper.tools.r8.graph.DexProgramClass[] getClassMap() {
    assert classes != null;
    return classes;
  }

  public DexMethod[] getMethodMap() {
    assert methods != null;
    return methods;
  }

  public com.debughelper.tools.r8.graph.DexProto[] getProtosMap() {
    assert protos != null;
    return protos;
  }

  public DexField[] getFieldMap() {
    assert fields != null;
    return fields;
  }

  public com.debughelper.tools.r8.graph.DexType[] getTypeMap() {
    assert types != null;
    return types;
  }

  public DexString[] getStringMap() {
    assert strings != null;
    return strings;
  }

  public DexCallSite[] getCallSiteMap() {
    assert callSites != null;
    return callSites;
  }

  public DexMethodHandle[] getMethodHandleMap() {
    assert methodHandles != null;
    return methodHandles;
  }

  public com.debughelper.tools.r8.graph.DexProgramClass getClass(int index) {
    assert classes[index] != null;
    return classes[index];
  }

  public DexMethod getMethod(int index) {
    assert methods[index] != null;
    return methods[index];
  }

  public com.debughelper.tools.r8.graph.DexProto getProto(int index) {
    assert protos[index] != null;
    return protos[index];
  }

  public DexField getField(int index) {
    assert fields[index] != null;
    return fields[index];
  }

  public com.debughelper.tools.r8.graph.DexType getType(int index) {
    assert types[index] != null;
    return types[index];
  }

  public DexString getString(int index) {
    assert strings[index] != null;
    return strings[index];
  }

  public DexCallSite getCallSite(int index) {
    assert callSites[index] != null;
    return callSites[index];
  }

  public DexMethodHandle getMethodHandle(int index) {
    assert methodHandles[index] != null;
    return methodHandles[index];
  }

  public void setClass(int index, DexProgramClass clazz) {
    assert classes[index] == null;
    classes[index] = clazz;
  }

  public void setProto(int index, DexProto proto) {
    assert protos[index] == null;
    protos[index] = proto;
  }

  public void setMethod(int index, DexMethod method) {
    assert methods[index] == null;
    methods[index] = method;
  }

  public void setField(int index, DexField field) {
    assert fields[index] == null;
    fields[index] = field;
  }

  public void setType(int index, DexType type) {
    assert types[index] == null;
    types[index] = type;
  }

  public void setString(int index, DexString string) {
    assert strings[index] == null;
    strings[index] = string;
  }

  public void setCallSites(int index, DexCallSite callSite) {
    assert callSites[index] == null;
    callSites[index] = callSite;
  }

  public void setMethodHandle(int index, DexMethodHandle methodHandle) {
    assert methodHandles[index] == null;
    methodHandles[index] = methodHandle;
  }
}
