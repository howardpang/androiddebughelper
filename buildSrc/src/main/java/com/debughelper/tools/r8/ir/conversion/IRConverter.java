// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.debughelper.tools.r8.ir.conversion;

import static com.debughelper.tools.r8.ir.desugar.InterfaceMethodRewriter.Flavor.ExcludeDexResources;
import static com.debughelper.tools.r8.ir.desugar.InterfaceMethodRewriter.Flavor.IncludeAllResources;

import com.debughelper.tools.r8.graph.DexApplication;
import com.debughelper.tools.r8.graph.DexApplication.Builder;
import com.debughelper.tools.r8.ir.analysis.constant.SparseConditionalConstantPropagation;
import com.debughelper.tools.r8.ir.analysis.type.TypeAnalysis;
import com.debughelper.tools.r8.ir.analysis.type.TypeEnvironment;
import com.debughelper.tools.r8.ir.conversion.CallSiteInformation;
import com.debughelper.tools.r8.ir.conversion.LensCodeRewriter;
import com.debughelper.tools.r8.ir.conversion.OptimizationFeedback;
import com.debughelper.tools.r8.ir.conversion.OptimizationFeedbackDirect;
import com.debughelper.tools.r8.ir.conversion.OptimizationFeedbackIgnore;
import com.debughelper.tools.r8.ir.desugar.CovariantReturnTypeAnnotationTransformer;
import com.debughelper.tools.r8.ir.desugar.InterfaceMethodRewriter;
import com.debughelper.tools.r8.ir.optimize.CodeRewriter;
import com.debughelper.tools.r8.ir.optimize.ConstantCanonicalizer;
import com.debughelper.tools.r8.ir.optimize.DeadCodeRemover;
import com.debughelper.tools.r8.ir.optimize.Devirtualizer;
import com.debughelper.tools.r8.ir.optimize.Inliner;
import com.debughelper.tools.r8.ir.optimize.Inliner.Constraint;
import com.debughelper.tools.r8.ir.optimize.MemberValuePropagation;
import com.debughelper.tools.r8.ir.optimize.NonNullTracker;
import com.debughelper.tools.r8.ir.optimize.Outliner;
import com.debughelper.tools.r8.ir.optimize.PeepholeOptimizer;
import com.debughelper.tools.r8.ir.optimize.classinliner.ClassInliner;
import com.debughelper.tools.r8.ir.optimize.lambda.LambdaMerger;
import com.debughelper.tools.r8.utils.InternalOptions;
import com.debughelper.tools.r8.utils.InternalOptions.OutlineOptions;
import com.debughelper.tools.r8.errors.Unreachable;
import com.debughelper.tools.r8.graph.AppInfo;
import com.debughelper.tools.r8.graph.AppInfoWithSubtyping;
import com.debughelper.tools.r8.graph.CfCode;
import com.debughelper.tools.r8.graph.Code;
import com.debughelper.tools.r8.graph.DexAnnotation;
import com.debughelper.tools.r8.graph.DexEncodedMethod;
import com.debughelper.tools.r8.graph.DexItemFactory;
import com.debughelper.tools.r8.graph.DexMethod;
import com.debughelper.tools.r8.graph.DexProgramClass;
import com.debughelper.tools.r8.graph.DexString;
import com.debughelper.tools.r8.graph.DexType;
import com.debughelper.tools.r8.graph.DexTypeList;
import com.debughelper.tools.r8.graph.GraphLense;
import com.debughelper.tools.r8.ir.code.AlwaysMaterializingDefinition;
import com.debughelper.tools.r8.ir.code.AlwaysMaterializingUser;
import com.debughelper.tools.r8.ir.code.BasicBlock;
import com.debughelper.tools.r8.ir.code.IRCode;
import com.debughelper.tools.r8.ir.code.Instruction;
import com.debughelper.tools.r8.ir.code.InstructionListIterator;
import com.debughelper.tools.r8.ir.code.Value;
import com.debughelper.tools.r8.ir.code.ValueType;
import com.debughelper.tools.r8.ir.desugar.LambdaRewriter;
import com.debughelper.tools.r8.ir.desugar.StringConcatRewriter;
import com.debughelper.tools.r8.ir.regalloc.LinearScanRegisterAllocator;
import com.debughelper.tools.r8.ir.regalloc.RegisterAllocator;
import com.debughelper.tools.r8.logging.Log;
import com.debughelper.tools.r8.naming.IdentifierNameStringMarker;
import com.debughelper.tools.r8.shaking.protolite.ProtoLitePruner;
import com.debughelper.tools.r8.utils.CfgPrinter;
import com.debughelper.tools.r8.utils.DescriptorUtils;
import com.debughelper.tools.r8.utils.StringDiagnostic;
import com.debughelper.tools.r8.utils.ThreadUtils;
import com.debughelper.tools.r8.utils.Timing;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ListMultimap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

public class IRConverter {

  private static final int PEEPHOLE_OPTIMIZATION_PASSES = 2;

  private final com.debughelper.tools.r8.utils.Timing timing;
  public final com.debughelper.tools.r8.graph.AppInfo appInfo;
  private final Outliner outliner;
  private final com.debughelper.tools.r8.ir.desugar.StringConcatRewriter stringConcatRewriter;
  private final com.debughelper.tools.r8.ir.desugar.LambdaRewriter lambdaRewriter;
  private final com.debughelper.tools.r8.ir.desugar.InterfaceMethodRewriter interfaceMethodRewriter;
  private final LambdaMerger lambdaMerger;
  private final ClassInliner classInliner;
  private final com.debughelper.tools.r8.utils.InternalOptions options;
  private final com.debughelper.tools.r8.utils.CfgPrinter printer;
  private final com.debughelper.tools.r8.graph.GraphLense graphLense;
  private final CodeRewriter codeRewriter;
  private final MemberValuePropagation memberValuePropagation;
  private final com.debughelper.tools.r8.ir.conversion.LensCodeRewriter lensCodeRewriter;
  private final NonNullTracker nonNullTracker;
  private final Inliner inliner;
  private final com.debughelper.tools.r8.shaking.protolite.ProtoLitePruner protoLiteRewriter;
  private final com.debughelper.tools.r8.naming.IdentifierNameStringMarker identifierNameStringMarker;
  private final Devirtualizer devirtualizer;
  private final CovariantReturnTypeAnnotationTransformer covariantReturnTypeAnnotationTransformer;

  private final com.debughelper.tools.r8.ir.conversion.OptimizationFeedback ignoreOptimizationFeedback = new OptimizationFeedbackIgnore();
  private final com.debughelper.tools.r8.ir.conversion.OptimizationFeedback simpleOptimizationFeedback = new OptimizationFeedbackSimple();
  private com.debughelper.tools.r8.graph.DexString highestSortingString;

  private IRConverter(
      com.debughelper.tools.r8.graph.AppInfo appInfo,
      com.debughelper.tools.r8.utils.InternalOptions options,
      com.debughelper.tools.r8.utils.Timing timing,
      com.debughelper.tools.r8.utils.CfgPrinter printer,
      com.debughelper.tools.r8.graph.GraphLense graphLense,
      boolean enableWholeProgramOptimizations) {
    assert appInfo != null;
    assert options != null;
    assert options.programConsumer != null;
    this.timing = timing != null ? timing : new com.debughelper.tools.r8.utils.Timing("internal");
    this.appInfo = appInfo;
    this.graphLense = graphLense != null ? graphLense : com.debughelper.tools.r8.graph.GraphLense.getIdentityLense();
    this.options = options;
    this.printer = printer;
    this.codeRewriter = new CodeRewriter(appInfo, libraryMethodsReturningReceiver(), options);
    this.stringConcatRewriter = new StringConcatRewriter(options.itemFactory);
    this.lambdaRewriter = options.enableDesugaring ? new LambdaRewriter(this) : null;
    this.interfaceMethodRewriter =
        (options.enableDesugaring && enableInterfaceMethodDesugaring())
            ? new com.debughelper.tools.r8.ir.desugar.InterfaceMethodRewriter(this, options) : null;
    this.lambdaMerger = options.enableLambdaMerging
        ? new LambdaMerger(appInfo.dexItemFactory, options.reporter) : null;
    this.covariantReturnTypeAnnotationTransformer =
        options.processCovariantReturnTypeAnnotations
            ? new CovariantReturnTypeAnnotationTransformer(this, appInfo.dexItemFactory)
            : null;
    if (enableWholeProgramOptimizations) {
      assert appInfo.hasLiveness();
      this.nonNullTracker = new NonNullTracker();
      this.inliner = new Inliner(appInfo.withLiveness(), graphLense, options);
      this.outliner = new Outliner(appInfo.withLiveness(), options);
      this.memberValuePropagation =
          options.enableValuePropagation ?
              new MemberValuePropagation(appInfo.withLiveness()) : null;
      this.lensCodeRewriter = new LensCodeRewriter(graphLense, appInfo.withSubtyping(), options);
      if (appInfo.hasLiveness()) {
        // When disabling the pruner here, also disable the ProtoLiteExtension in R8.java.
        this.protoLiteRewriter =
            options.forceProguardCompatibility ? null : new ProtoLitePruner(appInfo.withLiveness());
        if (!appInfo.withLiveness().identifierNameStrings.isEmpty() && options.enableMinification) {
          this.identifierNameStringMarker = new IdentifierNameStringMarker(appInfo.withLiveness());
        } else {
          this.identifierNameStringMarker = null;
        }
        this.devirtualizer =
            options.enableDevirtualization ? new Devirtualizer(appInfo.withLiveness()) : null;
      } else {
        this.protoLiteRewriter = null;
        this.identifierNameStringMarker = null;
        this.devirtualizer = null;
      }
    } else {
      this.nonNullTracker = null;
      this.inliner = null;
      this.outliner = null;
      this.memberValuePropagation = null;
      this.lensCodeRewriter = null;
      this.protoLiteRewriter = null;
      this.identifierNameStringMarker = null;
      this.devirtualizer = null;
    }
    this.classInliner =
        (options.enableClassInlining && options.enableInlining && inliner != null)
            ? new ClassInliner(appInfo.dexItemFactory, options.classInliningInstructionLimit)
            : null;
  }

  /**
   * Create an IR converter for processing methods with full program optimization disabled.
   */
  public IRConverter(
      com.debughelper.tools.r8.graph.AppInfo appInfo,
      com.debughelper.tools.r8.utils.InternalOptions options) {
    this(appInfo, options, null, null, null, false);
  }

  /**
   * Create an IR converter for processing methods with full program optimization disabled.
   */
  public IRConverter(
      AppInfo appInfo,
      com.debughelper.tools.r8.utils.InternalOptions options,
      com.debughelper.tools.r8.utils.Timing timing,
      com.debughelper.tools.r8.utils.CfgPrinter printer) {
    this(appInfo, options, timing, printer, null, false);
  }

  /**
   * Create an IR converter for processing methods with full program optimization enabled.
   */
  public IRConverter(
      AppInfoWithSubtyping appInfo,
      com.debughelper.tools.r8.utils.InternalOptions options,
      Timing timing,
      CfgPrinter printer,
      GraphLense graphLense) {
    this(appInfo, options, timing, printer, graphLense, true);
  }

  private boolean enableInterfaceMethodDesugaring() {
    switch (options.interfaceMethodDesugaring) {
      case Off:
        return false;
      case Auto:
        return !options.canUseDefaultAndStaticInterfaceMethods();
    }
    throw new com.debughelper.tools.r8.errors.Unreachable();
  }

  private boolean enableTryWithResourcesDesugaring() {
    switch (options.tryWithResourcesDesugaring) {
      case Off:
        return false;
      case Auto:
        return !options.canUseSuppressedExceptions();
    }
    throw new Unreachable();
  }

  private Set<com.debughelper.tools.r8.graph.DexMethod> libraryMethodsReturningReceiver() {
    Set<DexMethod> methods = new HashSet<>();
    DexItemFactory dexItemFactory = appInfo.dexItemFactory;
    dexItemFactory.stringBufferMethods.forEachAppendMethod(methods::add);
    dexItemFactory.stringBuilderMethods.forEachAppendMethod(methods::add);
    return methods;
  }

  private void removeLambdaDeserializationMethods() {
    if (lambdaRewriter != null) {
      lambdaRewriter.removeLambdaDeserializationMethods(appInfo.classes());
    }
  }

  private void synthesizeLambdaClasses(com.debughelper.tools.r8.graph.DexApplication.Builder<?> builder) {
    if (lambdaRewriter != null) {
      lambdaRewriter.adjustAccessibility();
      lambdaRewriter.synthesizeLambdaClasses(builder);
    }
  }

  private void desugarInterfaceMethods(
          com.debughelper.tools.r8.graph.DexApplication.Builder<?> builder, com.debughelper.tools.r8.ir.desugar.InterfaceMethodRewriter.Flavor includeAllResources) {
    if (interfaceMethodRewriter != null) {
      interfaceMethodRewriter.desugarInterfaceMethods(builder, includeAllResources);
    }
  }

  private void processCovariantReturnTypeAnnotations(com.debughelper.tools.r8.graph.DexApplication.Builder<?> builder) {
    if (covariantReturnTypeAnnotationTransformer != null) {
      covariantReturnTypeAnnotationTransformer.process(builder);
    }
  }

  public com.debughelper.tools.r8.graph.DexApplication convertToDex(com.debughelper.tools.r8.graph.DexApplication application, ExecutorService executor)
      throws ExecutionException {
    removeLambdaDeserializationMethods();

    timing.begin("IR conversion");
    convertClassesToDex(application.classes(), executor);

    // Build a new application with jumbo string info,
    com.debughelper.tools.r8.graph.DexApplication.Builder<?> builder = application.builder();
    builder.setHighestSortingString(highestSortingString);

    synthesizeLambdaClasses(builder);
    desugarInterfaceMethods(builder, com.debughelper.tools.r8.ir.desugar.InterfaceMethodRewriter.Flavor.ExcludeDexResources);
    processCovariantReturnTypeAnnotations(builder);

    handleSynthesizedClassMapping(builder);
    timing.end();

    return builder.build();
  }

  private void handleSynthesizedClassMapping(com.debughelper.tools.r8.graph.DexApplication.Builder<?> builder) {
    if (options.intermediate) {
      updateSynthesizedClassMapping(builder);
    }

    updateMainDexListWithSynthesizedClassMap(builder);

    if (!options.intermediate) {
      clearSynthesizedClassMapping(builder);
    }
  }

  private void updateMainDexListWithSynthesizedClassMap(com.debughelper.tools.r8.graph.DexApplication.Builder<?> builder) {
    Set<com.debughelper.tools.r8.graph.DexType> inputMainDexList = builder.getMainDexList();
    if (!inputMainDexList.isEmpty()) {
      Map<com.debughelper.tools.r8.graph.DexType, com.debughelper.tools.r8.graph.DexProgramClass> programClasses = builder.getProgramClasses().stream()
          .collect(Collectors.toMap(
              programClass -> programClass.type,
              Function.identity()));
      Collection<com.debughelper.tools.r8.graph.DexType> synthesized = new ArrayList<>();
      for (com.debughelper.tools.r8.graph.DexType dexType : inputMainDexList) {
        com.debughelper.tools.r8.graph.DexProgramClass programClass = programClasses.get(dexType);
        if (programClass != null) {
          synthesized.addAll(com.debughelper.tools.r8.graph.DexAnnotation.readAnnotationSynthesizedClassMap(
              programClass, builder.dexItemFactory));
        }
      }
      builder.addToMainDexList(synthesized);
    }
  }

  private void clearSynthesizedClassMapping(com.debughelper.tools.r8.graph.DexApplication.Builder<?> builder) {
    for (com.debughelper.tools.r8.graph.DexProgramClass programClass : builder.getProgramClasses()) {
      programClass.annotations =
          programClass.annotations.getWithout(builder.dexItemFactory.annotationSynthesizedClassMap);
    }
  }

  private void updateSynthesizedClassMapping(com.debughelper.tools.r8.graph.DexApplication.Builder<?> builder) {
    ListMultimap<com.debughelper.tools.r8.graph.DexProgramClass, com.debughelper.tools.r8.graph.DexProgramClass> originalToSynthesized =
        ArrayListMultimap.create();
    for (com.debughelper.tools.r8.graph.DexProgramClass synthesized : builder.getSynthesizedClasses()) {
      for (com.debughelper.tools.r8.graph.DexProgramClass original : synthesized.getSynthesizedFrom()) {
        originalToSynthesized.put(original, synthesized);
      }
    }

    for (Map.Entry<com.debughelper.tools.r8.graph.DexProgramClass, Collection<com.debughelper.tools.r8.graph.DexProgramClass>> entry :
        originalToSynthesized.asMap().entrySet()) {
      com.debughelper.tools.r8.graph.DexProgramClass original = entry.getKey();
      // Use a tree set to make sure that we have an ordering on the types.
      // These types are put in an array in annotations in the output and we
      // need a consistent ordering on them.
      TreeSet<com.debughelper.tools.r8.graph.DexType> synthesized = new TreeSet<>(com.debughelper.tools.r8.graph.DexType::slowCompareTo);
      entry.getValue()
          .stream()
          .map(dexProgramClass -> dexProgramClass.type)
          .forEach(synthesized::add);
      synthesized.addAll(
          com.debughelper.tools.r8.graph.DexAnnotation.readAnnotationSynthesizedClassMap(original, builder.dexItemFactory));

      com.debughelper.tools.r8.graph.DexAnnotation updatedAnnotation =
          DexAnnotation.createAnnotationSynthesizedClassMap(synthesized, builder.dexItemFactory);

      original.annotations = original.annotations.getWithAddedOrReplaced(updatedAnnotation);
    }
  }

  private void convertClassesToDex(Iterable<com.debughelper.tools.r8.graph.DexProgramClass> classes,
      ExecutorService executor) throws ExecutionException {
    List<Future<?>> futures = new ArrayList<>();
    for (com.debughelper.tools.r8.graph.DexProgramClass clazz : classes) {
      futures.add(executor.submit(() -> convertMethodsToDex(clazz)));
    }
    com.debughelper.tools.r8.utils.ThreadUtils.awaitFutures(futures);
  }

  private void convertMethodsToDex(com.debughelper.tools.r8.graph.DexProgramClass clazz) {
    // When converting all methods on a class always convert <clinit> first.
    for (com.debughelper.tools.r8.graph.DexEncodedMethod method : clazz.directMethods()) {
      if (method.isClassInitializer()) {
        convertMethodToDex(method);
        break;
      }
    }
    clazz.forEachMethod(method -> {
      if (!method.isClassInitializer()) {
        convertMethodToDex(method);
      }
    });
  }

  private void convertMethodToDex(com.debughelper.tools.r8.graph.DexEncodedMethod method) {
    assert options.isGeneratingDex();
    if (method.getCode() != null) {
      boolean matchesMethodFilter = options.methodMatchesFilter(method);
      if (matchesMethodFilter) {
        if (!(options.passthroughDexCode && method.getCode().isDexCode())) {
          // We do not process in call graph order, so anything could be a leaf.
          rewriteCode(method, simpleOptimizationFeedback, x -> true, com.debughelper.tools.r8.ir.conversion.CallSiteInformation.empty(),
              Outliner::noProcessing);
        }
        updateHighestSortingStrings(method);
      }
    }
  }

  public com.debughelper.tools.r8.graph.DexApplication optimize(com.debughelper.tools.r8.graph.DexApplication application) throws ExecutionException {
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      return optimize(application, executor);
    } finally {
      executor.shutdown();
    }
  }

  public com.debughelper.tools.r8.graph.DexApplication optimize(com.debughelper.tools.r8.graph.DexApplication application, ExecutorService executorService)
      throws ExecutionException {
    removeLambdaDeserializationMethods();
    collectLambdaMergingCandidates(application);

    // The process is in two phases.
    // 1) Subject all DexEncodedMethods to optimization (except outlining).
    //    - a side effect is candidates for outlining are identified.
    // 2) Perform outlining for the collected candidates.
    // Ideally, we should outline eagerly when threshold for a template has been reached.

    // Process the application identifying outlining candidates.
    com.debughelper.tools.r8.ir.conversion.OptimizationFeedback directFeedback = new OptimizationFeedbackDirect();
    {
      timing.begin("Build call graph");
      CallGraph callGraph = CallGraph
          .build(application, appInfo.withLiveness(), graphLense, options);
      timing.end();
      timing.begin("IR conversion phase 1");
      BiConsumer<com.debughelper.tools.r8.ir.code.IRCode, com.debughelper.tools.r8.graph.DexEncodedMethod> outlineHandler =
          outliner == null ? Outliner::noProcessing : outliner.identifyCandidateMethods();
      callGraph.forEachMethod(
          (method, isProcessedConcurrently) -> {
            processMethod(
                method, directFeedback, isProcessedConcurrently, callGraph, outlineHandler);
          },
          executorService);
      timing.end();
    }

    // Build a new application with jumbo string info.
    com.debughelper.tools.r8.graph.DexApplication.Builder<?> builder = application.builder();
    builder.setHighestSortingString(highestSortingString);

    // Second inlining pass for dealing with double inline callers.
    if (inliner != null) {
      // Use direct feedback still, since methods after inlining may
      // change their status or other properties.
      inliner.processDoubleInlineCallers(this, directFeedback);
    }

    synthesizeLambdaClasses(builder);
    desugarInterfaceMethods(builder, com.debughelper.tools.r8.ir.desugar.InterfaceMethodRewriter.Flavor.IncludeAllResources);

    handleSynthesizedClassMapping(builder);
    finalizeLambdaMerging(application, directFeedback, builder, executorService);

    if (outliner != null) {
      timing.begin("IR conversion phase 2");
      if (outliner.selectMethodsForOutlining()) {
        forEachSelectedOutliningMethod(
            executorService,
            (code, method) -> {
              printMethod(code, "IR before outlining (SSA)");
              outliner.identifyOutlineSites(code, method);
            });
        com.debughelper.tools.r8.graph.DexProgramClass outlineClass = outliner.buildOutlinerClass(computeOutlineClassType());
        optimizeSynthesizedClass(outlineClass);
        forEachSelectedOutliningMethod(
            executorService,
            (code, method) -> {
              outliner.applyOutliningCandidate(code, method);
              printMethod(code, "IR after outlining (SSA)");
              finalizeIR(method, code, ignoreOptimizationFeedback);
            });
        assert outliner.checkAllOutlineSitesFoundAgain();
        builder.addSynthesizedClass(outlineClass, true);
        clearDexMethodCompilationState(outlineClass);
      }
      timing.end();
    }
    clearDexMethodCompilationState();

    if (identifierNameStringMarker != null) {
      identifierNameStringMarker.decoupleIdentifierNameStringsInFields();
    }

    return builder.build();
  }

  private void forEachSelectedOutliningMethod(
      ExecutorService executorService, BiConsumer<com.debughelper.tools.r8.ir.code.IRCode, com.debughelper.tools.r8.graph.DexEncodedMethod> consumer)
      throws ExecutionException {
    assert !options.skipIR;
    Set<com.debughelper.tools.r8.graph.DexEncodedMethod> methods = outliner.getMethodsSelectedForOutlining();
    List<Future<?>> futures = new ArrayList<>();
    for (com.debughelper.tools.r8.graph.DexEncodedMethod method : methods) {
      futures.add(
          executorService.submit(
              () -> {
                com.debughelper.tools.r8.ir.code.IRCode code =
                    method.buildIR(appInfo, options, appInfo.originFor(method.method.holder));
                assert code != null;
                assert !method.getCode().isOutlineCode();
                // Instead of repeating all the optimizations of rewriteCode(), only run the
                // optimizations needed for outlining: rewriteMoveResult() to remove out-values on
                // StringBuilder/StringBuffer method invocations, and removeDeadCode() to remove
                // unused out-values.
                codeRewriter.rewriteMoveResult(code);
                DeadCodeRemover.removeDeadCode(code, codeRewriter, graphLense, options);
                consumer.accept(code, method);
                return null;
              }));
    }
    ThreadUtils.awaitFutures(futures);
  }

  private void collectLambdaMergingCandidates(com.debughelper.tools.r8.graph.DexApplication application) {
    if (lambdaMerger != null) {
      lambdaMerger.collectGroupCandidates(application, appInfo.withLiveness(), options);
    }
  }

  private void finalizeLambdaMerging(
      com.debughelper.tools.r8.graph.DexApplication application,
      com.debughelper.tools.r8.ir.conversion.OptimizationFeedback directFeedback,
      com.debughelper.tools.r8.graph.DexApplication.Builder<?> builder,
      ExecutorService executorService)
      throws ExecutionException {
    if (lambdaMerger != null) {
      lambdaMerger.applyLambdaClassMapping(
          application, this, directFeedback, builder, executorService);
    }
  }

  private void clearDexMethodCompilationState() {
    appInfo.classes().forEach(this::clearDexMethodCompilationState);
  }

  private void clearDexMethodCompilationState(com.debughelper.tools.r8.graph.DexProgramClass clazz) {
    clazz.forEachMethod(com.debughelper.tools.r8.graph.DexEncodedMethod::markNotProcessed);
  }

  /**
   * This will replace the Dex code in the method with the Dex code generated from the provided IR.
   * <p>
   * This method is *only* intended for testing, where tests manipulate the IR and need runnable Dex
   * code.
   *
   * @param method the method to replace code for
   * @param code the IR code for the method
   */
  public void replaceCodeForTesting(com.debughelper.tools.r8.graph.DexEncodedMethod method, com.debughelper.tools.r8.ir.code.IRCode code) {
    if (com.debughelper.tools.r8.logging.Log.ENABLED) {
      com.debughelper.tools.r8.logging.Log.debug(getClass(), "Initial (SSA) flow graph for %s:\n%s", method.toSourceString(), code);
    }
    assert code.isConsistentSSA();
    code.traceBlocks();
    com.debughelper.tools.r8.ir.regalloc.RegisterAllocator registerAllocator = performRegisterAllocation(code, method);
    method.setCode(code, registerAllocator, options);
    if (com.debughelper.tools.r8.logging.Log.ENABLED) {
      com.debughelper.tools.r8.logging.Log.debug(getClass(), "Resulting dex code for %s:\n%s",
          method.toSourceString(), logCode(options, method));
    }
  }

  // Find an unused name for the outlining class. When multiple runs produces additional
  // outlining the default outlining class might already be present.
  private com.debughelper.tools.r8.graph.DexType computeOutlineClassType() {
    DexType result;
    int count = 0;
    do {
      String name = com.debughelper.tools.r8.utils.InternalOptions.OutlineOptions.CLASS_NAME + (count == 0 ? "" : Integer.toString(count));
      count++;
      result = appInfo.dexItemFactory.createType(DescriptorUtils.javaTypeToDescriptor(name));
    } while (appInfo.definitionFor(result) != null);
    // Register the newly generated type in the subtyping hierarchy, if we have one.
    appInfo.registerNewType(result, appInfo.dexItemFactory.objectType);
    return result;
  }

  public void optimizeSynthesizedClass(DexProgramClass clazz) {
    try {
      codeRewriter.enterCachedClass(clazz);
      // Process the generated class, but don't apply any outlining.
      clazz.forEachMethodThrowing(this::optimizeSynthesizedMethod);
    } finally {
      codeRewriter.leaveCachedClass(clazz);
    }
  }

  public void optimizeSynthesizedMethod(com.debughelper.tools.r8.graph.DexEncodedMethod method) {
    // Process the generated method, but don't apply any outlining.
    processMethod(method, ignoreOptimizationFeedback, x -> false, com.debughelper.tools.r8.ir.conversion.CallSiteInformation.empty(),
        Outliner::noProcessing);
  }

  private String logCode(com.debughelper.tools.r8.utils.InternalOptions options, com.debughelper.tools.r8.graph.DexEncodedMethod method) {
    return options.useSmaliSyntax ? method.toSmaliString(null) : method.codeToString();
  }

  public void processMethod(
      com.debughelper.tools.r8.graph.DexEncodedMethod method,
      com.debughelper.tools.r8.ir.conversion.OptimizationFeedback feedback,
      Predicate<com.debughelper.tools.r8.graph.DexEncodedMethod> isProcessedConcurrently,
      com.debughelper.tools.r8.ir.conversion.CallSiteInformation callSiteInformation,
      BiConsumer<com.debughelper.tools.r8.ir.code.IRCode, com.debughelper.tools.r8.graph.DexEncodedMethod> outlineHandler) {
    Code code = method.getCode();
    boolean matchesMethodFilter = options.methodMatchesFilter(method);
    if (code != null && matchesMethodFilter) {
      rewriteCode(method, feedback, isProcessedConcurrently, callSiteInformation, outlineHandler);
    } else {
      // Mark abstract methods as processed as well.
      method.markProcessed(Constraint.NEVER);
    }
  }

  private static void invertConditionalsForTesting(com.debughelper.tools.r8.ir.code.IRCode code) {
    for (com.debughelper.tools.r8.ir.code.BasicBlock block : code.blocks) {
      if (block.exit().isIf()) {
        block.exit().asIf().invert();
      }
    }
  }

  private void rewriteCode(
      com.debughelper.tools.r8.graph.DexEncodedMethod method,
      com.debughelper.tools.r8.ir.conversion.OptimizationFeedback feedback,
      Predicate<com.debughelper.tools.r8.graph.DexEncodedMethod> isProcessedConcurrently,
      CallSiteInformation callSiteInformation,
      BiConsumer<com.debughelper.tools.r8.ir.code.IRCode, com.debughelper.tools.r8.graph.DexEncodedMethod> outlineHandler) {
    if (options.verbose) {
      options.reporter.info(
          new StringDiagnostic("Processing: " + method.toSourceString()));
    }
    if (com.debughelper.tools.r8.logging.Log.ENABLED) {
      com.debughelper.tools.r8.logging.Log.debug(getClass(), "Original code for %s:\n%s",
          method.toSourceString(), logCode(options, method));
    }
    if (options.skipIR) {
      feedback.markProcessed(method, Constraint.NEVER);
      return;
    }
    com.debughelper.tools.r8.ir.code.IRCode code = method.buildIR(appInfo, options, appInfo.originFor(method.method.holder));
    if (code == null) {
      feedback.markProcessed(method, Constraint.NEVER);
      return;
    }
    if (com.debughelper.tools.r8.logging.Log.ENABLED) {
      com.debughelper.tools.r8.logging.Log.debug(getClass(), "Initial (SSA) flow graph for %s:\n%s", method.toSourceString(), code);
    }
    // Compilation header if printing CFGs for this method.
    printC1VisualizerHeader(method);
    printMethod(code, "Initial IR (SSA)");

    if (options.canHaveArtStringNewInitBug()) {
      codeRewriter.ensureDirectStringNewToInit(code);
    }

    if (options.debug) {
      codeRewriter.simplifyDebugLocals(code);
    }

    if (!method.isProcessed()) {
      if (protoLiteRewriter != null && protoLiteRewriter.appliesTo(method)) {
        protoLiteRewriter.rewriteProtoLiteSpecialMethod(code, method);
      }
      if (lensCodeRewriter != null) {
        lensCodeRewriter.rewrite(code, method);
      } else {
        assert graphLense.isIdentityLense();
      }
    }

    if (identifierNameStringMarker != null) {
      identifierNameStringMarker.decoupleIdentifierNameStringsInMethod(method, code);
      assert code.isConsistentSSA();
    }

    if (memberValuePropagation != null) {
      memberValuePropagation.rewriteWithConstantValues(code, method.method.holder);
    }
    if (options.enableSwitchMapRemoval && appInfo.hasLiveness()) {
      codeRewriter.removeSwitchMaps(code);
    }
    if (options.disableAssertions) {
      codeRewriter.disableAssertions(appInfo, method, code, feedback);
    }
    if (options.enableNonNullTracking && nonNullTracker != null) {
      nonNullTracker.addNonNull(code);
      assert code.isConsistentSSA();
    }
    TypeEnvironment typeEnvironment = TypeAnalysis.getDefaultTypeEnvironment();
    if (options.enableInlining && inliner != null) {
      typeEnvironment = new TypeAnalysis(appInfo, method, code);
      // TODO(zerny): Should we support inlining in debug mode? b/62937285
      assert !options.debug;
      inliner.performInlining(
          method, code, typeEnvironment, isProcessedConcurrently, callSiteInformation);
    }
    if (devirtualizer != null) {
      devirtualizer.devirtualizeInvokeInterface(code, typeEnvironment, method.method.getHolder());
    }
    codeRewriter.removeCasts(code, typeEnvironment);
    codeRewriter.rewriteLongCompareAndRequireNonNull(code, options);
    codeRewriter.commonSubexpressionElimination(code);
    codeRewriter.simplifyArrayConstruction(code);
    codeRewriter.rewriteMoveResult(code);
    codeRewriter.splitRangeInvokeConstants(code);
    new SparseConditionalConstantPropagation(code).run();
    codeRewriter.rewriteSwitch(code);
    codeRewriter.processMethodsNeverReturningNormally(code);
    codeRewriter.simplifyIf(code, typeEnvironment);

    if (options.testing.invertConditionals) {
      invertConditionalsForTesting(code);
    }

    if (options.enableNonNullTracking && nonNullTracker != null) {
      nonNullTracker.cleanupNonNull(code);
      assert code.isConsistentSSA();
    }
    if (!options.debug) {
      codeRewriter.collectClassInitializerDefaults(method, code);
    }
    if (com.debughelper.tools.r8.logging.Log.ENABLED) {
      com.debughelper.tools.r8.logging.Log.debug(getClass(), "Intermediate (SSA) flow graph for %s:\n%s",
          method.toSourceString(), code);
    }
    // Dead code removal. Performed after simplifications to remove code that becomes dead
    // as a result of those simplifications. The following optimizations could reveal more
    // dead code which is removed right before register allocation in performRegisterAllocation.
    DeadCodeRemover.removeDeadCode(code, codeRewriter, graphLense, options);
    assert code.isConsistentSSA();

    if (options.enableDesugaring && enableTryWithResourcesDesugaring()) {
      codeRewriter.rewriteThrowableAddAndGetSuppressed(code);
    }

    stringConcatRewriter.desugarStringConcats(method.method, code);

    if (lambdaRewriter != null) {
      lambdaRewriter.desugarLambdas(method, code);
      assert code.isConsistentSSA();
    }

    if (interfaceMethodRewriter != null) {
      interfaceMethodRewriter.rewriteMethodReferences(method, code);
      assert code.isConsistentSSA();
    }
    if (lambdaMerger != null) {
      lambdaMerger.processMethodCode(method, code);
      assert code.isConsistentSSA();
    }

    if (classInliner != null) {
      assert options.enableInlining && inliner != null;
      classInliner.processMethodCode(
          appInfo.withSubtyping(), method, code, isProcessedConcurrently,
          methodsToInline -> inliner.performForcedInlining(method, code, methodsToInline)
      );
      assert code.isConsistentSSA();
    }

    if (options.outline.enabled) {
      outlineHandler.accept(code, method);
      assert code.isConsistentSSA();
    }

    ConstantCanonicalizer.canonicalize(code);
    codeRewriter.useDedicatedConstantForLitInstruction(code);
    codeRewriter.shortenLiveRanges(code);
    codeRewriter.identifyReturnsArgument(method, code, feedback);
    if (options.enableInlining && inliner != null) {
      codeRewriter.identifyInvokeSemanticsForInlining(method, code, feedback);
    }

    // Insert code to log arguments if requested.
    if (options.methodMatchesLogArgumentsFilter(method)) {
      codeRewriter.logArgumentTypes(method, code);
      assert code.isConsistentSSA();
    }

    // Analysis must be done after method is rewritten by logArgumentTypes()
    codeRewriter.identifyClassInlinerEligibility(method, code, feedback);

    if (options.canHaveNumberConversionRegisterAllocationBug()) {
      codeRewriter.workaroundNumberConversionRegisterAllocationBug(code);
    }

    printMethod(code, "Optimized IR (SSA)");
    finalizeIR(method, code, feedback);
  }

  private void finalizeIR(com.debughelper.tools.r8.graph.DexEncodedMethod method, com.debughelper.tools.r8.ir.code.IRCode code, com.debughelper.tools.r8.ir.conversion.OptimizationFeedback feedback) {
    code.traceBlocks();
    if (options.isGeneratingClassFiles()) {
      finalizeToCf(method, code, feedback);
    } else {
      assert options.isGeneratingDex();
      finalizeToDex(method, code, feedback);
    }
  }

  private void finalizeToCf(com.debughelper.tools.r8.graph.DexEncodedMethod method, com.debughelper.tools.r8.ir.code.IRCode code, com.debughelper.tools.r8.ir.conversion.OptimizationFeedback feedback) {
    assert !method.getCode().isDexCode();
    CfBuilder builder = new CfBuilder(method, code, options.itemFactory);
    CfCode result = builder.build(codeRewriter, graphLense, options, appInfo.withSubtyping());
    method.setCode(result);
    markProcessed(method, code, feedback);
  }

  private void finalizeToDex(com.debughelper.tools.r8.graph.DexEncodedMethod method, com.debughelper.tools.r8.ir.code.IRCode code, com.debughelper.tools.r8.ir.conversion.OptimizationFeedback feedback) {
    // Workaround massive dex2oat memory use for self-recursive methods.
    CodeRewriter.disableDex2OatInliningForSelfRecursiveMethods(code, options);
    // Perform register allocation.
    com.debughelper.tools.r8.ir.regalloc.RegisterAllocator registerAllocator = performRegisterAllocation(code, method);
    method.setCode(code, registerAllocator, options);
    updateHighestSortingStrings(method);
    if (com.debughelper.tools.r8.logging.Log.ENABLED) {
      com.debughelper.tools.r8.logging.Log.debug(getClass(), "Resulting dex code for %s:\n%s",
          method.toSourceString(), logCode(options, method));
    }
    printMethod(code, "Final IR (non-SSA)");
    markProcessed(method, code, feedback);
  }

  private void markProcessed(com.debughelper.tools.r8.graph.DexEncodedMethod method, com.debughelper.tools.r8.ir.code.IRCode code, OptimizationFeedback feedback) {
    // After all the optimizations have take place, we compute whether method should be inlinedex.
    Constraint state;
    if (!options.enableInlining || inliner == null) {
      state = Constraint.NEVER;
    } else {
      state = inliner.computeInliningConstraint(code, method);
    }
    feedback.markProcessed(method, state);
  }

  private synchronized void updateHighestSortingStrings(com.debughelper.tools.r8.graph.DexEncodedMethod method) {
    DexString highestSortingReferencedString = method.getCode().asDexCode().highestSortingString;
    if (highestSortingReferencedString != null) {
      if (highestSortingString == null
          || highestSortingReferencedString.slowCompareTo(highestSortingString) > 0) {
        highestSortingString = highestSortingReferencedString;
      }
    }
  }

  private RegisterAllocator performRegisterAllocation(com.debughelper.tools.r8.ir.code.IRCode code, com.debughelper.tools.r8.graph.DexEncodedMethod method) {
    // Always perform dead code elimination before register allocation. The register allocator
    // does not allow dead code (to make sure that we do not waste registers for unneeded values).
    DeadCodeRemover.removeDeadCode(code, codeRewriter, graphLense, options);
    materializeInstructionBeforeLongOperationsWorkaround(code);
    workaroundForwardingInitializerBug(code);
    com.debughelper.tools.r8.ir.regalloc.LinearScanRegisterAllocator registerAllocator = new LinearScanRegisterAllocator(code, options);
    registerAllocator.allocateRegisters(options.debug);
    if (options.canHaveExceptionTargetingLoopHeaderBug()) {
      codeRewriter.workaroundExceptionTargetingLoopHeaderBug(code);
    }
    printMethod(code, "After register allocation (non-SSA)");
    for (int i = 0; i < PEEPHOLE_OPTIMIZATION_PASSES; i++) {
      CodeRewriter.collapsTrivialGotos(method, code);
      PeepholeOptimizer.optimize(code, registerAllocator);
    }
    CodeRewriter.collapsTrivialGotos(method, code);
    if (com.debughelper.tools.r8.logging.Log.ENABLED) {
      Log.debug(getClass(), "Final (non-SSA) flow graph for %s:\n%s",
          method.toSourceString(), code);
    }
    return registerAllocator;
  }

  private void workaroundForwardingInitializerBug(com.debughelper.tools.r8.ir.code.IRCode code) {
    if (!options.canHaveForwardingInitInliningBug()) {
      return;
    }
    // Only constructors.
    if (!code.method.isInstanceInitializer()) {
      return;
    }
    // Only constructors with certain signatures.
    DexTypeList paramTypes = code.method.method.proto.parameters;
    if (paramTypes.size() != 3 ||
        paramTypes.values[0] != options.itemFactory.doubleType ||
        paramTypes.values[1] != options.itemFactory.doubleType ||
        !paramTypes.values[2].isClassType()) {
      return;
    }
    // Only if the constructor contains a super constructor call taking only parameters as
    // inputs.
    for (com.debughelper.tools.r8.ir.code.BasicBlock block : code.blocks) {
      com.debughelper.tools.r8.ir.code.InstructionListIterator it = block.listIterator();
      com.debughelper.tools.r8.ir.code.Instruction superConstructorCall = it.nextUntil((i) ->
          i.isInvokeDirect() &&
          i.asInvokeDirect().getInvokedMethod().name == options.itemFactory.constructorMethodName &&
          i.asInvokeDirect().arguments().size() == 4 &&
          i.asInvokeDirect().arguments().stream().allMatch(com.debughelper.tools.r8.ir.code.Value::isArgument));
      if (superConstructorCall != null) {
        // We force a materializing const instruction in front of the super call to make
        // sure that there is at least one temporary register in the method. That disables
        // the inlining that is crashing on these devices.
        ensureInstructionBefore(code, superConstructorCall, it);
        break;
      }
    }
  }

  private void materializeInstructionBeforeLongOperationsWorkaround(com.debughelper.tools.r8.ir.code.IRCode code) {
    if (!options.canHaveDex2OatLinkedListBug()) {
      return;
    }
    for (com.debughelper.tools.r8.ir.code.BasicBlock block : code.blocks) {
      com.debughelper.tools.r8.ir.code.InstructionListIterator it = block.listIterator();
      com.debughelper.tools.r8.ir.code.Instruction firstMaterializing =
          it.nextUntil(IRConverter::isMaterializingInstructionOnArtArmVersionM);
      if (needsInstructionBeforeLongOperation(firstMaterializing)) {
        ensureInstructionBefore(code, firstMaterializing, it);
      }
    }
  }

  private static void ensureInstructionBefore(
          com.debughelper.tools.r8.ir.code.IRCode code, com.debughelper.tools.r8.ir.code.Instruction addBefore, InstructionListIterator it) {
    // Force materialize a constant-zero before the long operation.
    com.debughelper.tools.r8.ir.code.Instruction check = it.previous();
    assert addBefore == check;
    // Forced definition of const-zero
    Value fixitValue = code.createValue(ValueType.INT);
    com.debughelper.tools.r8.ir.code.Instruction fixitDefinition = new AlwaysMaterializingDefinition(fixitValue);
    fixitDefinition.setBlock(addBefore.getBlock());
    fixitDefinition.setPosition(addBefore.getPosition());
    it.add(fixitDefinition);
    // Forced user of the forced definition to ensure it has a user and thus live range.
    com.debughelper.tools.r8.ir.code.Instruction fixitUser = new AlwaysMaterializingUser(fixitValue);
    fixitUser.setBlock(addBefore.getBlock());
    it.add(fixitUser);
  }

  private static boolean needsInstructionBeforeLongOperation(com.debughelper.tools.r8.ir.code.Instruction instruction) {
    // The cortex fixup will only trigger on long sub and long add instructions.
    if (!((instruction.isAdd() || instruction.isSub()) && instruction.outType().isWide())) {
      return false;
    }
    // If the block with the instruction is a fallthrough block, then it can't end up being
    // preceded by the incorrectly linked prologue/epilogue..
    com.debughelper.tools.r8.ir.code.BasicBlock block = instruction.getBlock();
    for (BasicBlock pred : block.getPredecessors()) {
      if (pred.exit().fallthroughBlock() == block) {
        return false;
      }
    }
    return true;
  }

  private static boolean isMaterializingInstructionOnArtArmVersionM(com.debughelper.tools.r8.ir.code.Instruction instruction) {
    return !instruction.isDebugInstruction()
        && !instruction.isMove()
        && !isPossiblyNonMaterializingLongOperationOnArtArmVersionM(instruction);
  }

  private static boolean isPossiblyNonMaterializingLongOperationOnArtArmVersionM(
      Instruction instruction) {
    return (instruction.isMul() || instruction.isDiv()) && instruction.outType().isWide();
  }

  private void printC1VisualizerHeader(DexEncodedMethod method) {
    if (printer != null) {
      printer.begin("compilation");
      printer.print("name \"").append(method.toSourceString()).append("\"").ln();
      printer.print("method \"").append(method.toSourceString()).append("\"").ln();
      printer.print("date 0").ln();
      printer.end("compilation");
    }
  }

  private void printMethod(IRCode code, String title) {
    if (printer != null) {
      printer.resetUnusedValue();
      printer.begin("cfg");
      printer.print("name \"").append(title).append("\"\n");
      code.print(printer);
      printer.end("cfg");
    }
  }
}
