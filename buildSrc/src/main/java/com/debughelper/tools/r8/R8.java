// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.debughelper.tools.r8;

import static com.debughelper.tools.r8.R8Command.USAGE_MESSAGE;

import com.debughelper.tools.r8.CompilationFailedException;
import com.debughelper.tools.r8.Keep;
import com.debughelper.tools.r8.R8Command;
import com.debughelper.tools.r8.Version;
import com.debughelper.tools.r8.dex.ApplicationReader;
import com.debughelper.tools.r8.dex.ApplicationWriter;
import com.debughelper.tools.r8.dex.Marker;
import com.debughelper.tools.r8.dex.Marker.Tool;
import com.debughelper.tools.r8.errors.CompilationError;
import com.debughelper.tools.r8.graph.AppInfoWithSubtyping;
import com.debughelper.tools.r8.graph.DexApplication;
import com.debughelper.tools.r8.graph.DexProgramClass;
import com.debughelper.tools.r8.graph.DexType;
import com.debughelper.tools.r8.graph.GraphLense;
import com.debughelper.tools.r8.ir.conversion.IRConverter;
import com.debughelper.tools.r8.ir.optimize.EnumOrdinalMapCollector;
import com.debughelper.tools.r8.ir.optimize.SwitchMapCollector;
import com.debughelper.tools.r8.jar.CfApplicationWriter;
import com.debughelper.tools.r8.kotlin.Kotlin;
import com.debughelper.tools.r8.naming.ClassNameMapper;
import com.debughelper.tools.r8.naming.Minifier;
import com.debughelper.tools.r8.naming.NamingLens;
import com.debughelper.tools.r8.naming.ProguardMapApplier;
import com.debughelper.tools.r8.naming.ProguardMapSupplier;
import com.debughelper.tools.r8.naming.SeedMapper;
import com.debughelper.tools.r8.naming.SourceFileRewriter;
import com.debughelper.tools.r8.optimize.ClassAndMemberPublicizer;
import com.debughelper.tools.r8.optimize.MemberRebindingAnalysis;
import com.debughelper.tools.r8.optimize.VisibilityBridgeRemover;
import com.debughelper.tools.r8.origin.CommandLineOrigin;
import com.debughelper.tools.r8.shaking.AbstractMethodRemover;
import com.debughelper.tools.r8.shaking.AnnotationRemover;
import com.debughelper.tools.r8.shaking.DiscardedChecker;
import com.debughelper.tools.r8.shaking.Enqueuer;
import com.debughelper.tools.r8.shaking.Enqueuer.AppInfoWithLiveness;
import com.debughelper.tools.r8.shaking.MainDexListBuilder;
import com.debughelper.tools.r8.shaking.ProguardClassFilter;
import com.debughelper.tools.r8.shaking.ProguardConfiguration;
import com.debughelper.tools.r8.shaking.ReasonPrinter;
import com.debughelper.tools.r8.shaking.RootSetBuilder;
import com.debughelper.tools.r8.shaking.RootSetBuilder.RootSet;
import com.debughelper.tools.r8.shaking.TreePruner;
import com.debughelper.tools.r8.shaking.VerticalClassMerger;
import com.debughelper.tools.r8.shaking.protolite.ProtoLiteExtension;
import com.debughelper.tools.r8.utils.AndroidApiLevel;
import com.debughelper.tools.r8.utils.AndroidApp;
import com.debughelper.tools.r8.utils.CfgPrinter;
import com.debughelper.tools.r8.utils.ExceptionUtils;
import com.debughelper.tools.r8.utils.FileUtils;
import com.debughelper.tools.r8.utils.InternalOptions;
import com.debughelper.tools.r8.utils.InternalOptions.LineNumberOptimization;
import com.debughelper.tools.r8.utils.LineNumberOptimizer;
import com.debughelper.tools.r8.utils.Reporter;
import com.debughelper.tools.r8.utils.StringDiagnostic;
import com.debughelper.tools.r8.utils.ThreadUtils;
import com.debughelper.tools.r8.utils.Timing;
import com.debughelper.tools.r8.utils.VersionProperties;
import com.google.common.io.ByteStreams;
import com.google.common.io.Closer;
import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.StandardOpenOption;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;

/**
 * The R8 compiler.
 *
 * <p>R8 performs whole-program optimizing compilation of Java bytecode. It supports compilation of
 * Java bytecode to Java bytecode or DEX bytecode. R8 supports tree-shaking the program to remove
 * unneeded code and it supports minification of the program names to reduce the size of the
 * resulting program.
 *
 * <p>The R8 API is intentionally limited and should "do the right thing" given a command. If this
 * API does not suffice please contact the D8Adapter/R8 team.
 *
 * <p>R8 supports some configuration using configuration files mostly compatible with the format of
 * the <a href="https://www.guardsquare.com/en/proguard">ProGuard</a> optimizer.
 *
 * <p>The compiler is invoked by calling {@link #run(com.debughelper.tools.r8.R8Command) R8.run} with an appropriate {link
 * R8Command}. For example:
 *
 * <pre>
 *   R8.run(R8Command.builder()
 *       .addProgramFiles(inputPathA, inputPathB)
 *       .addLibraryFiles(debughelperJar)
 *       .setOutput(outputPath, OutputMode.DexIndexed)
 *       .build());
 * </pre>
 *
 * The above reads the input files denoted by {@code inputPathA} and {@code inputPathB}, compiles
 * them to DEX bytecode, using {@code debughelperJar} as the reference of the system runtime library,
 * and then writes the result to the directory or zip archive specified by {@code outputPath}.
 */
@Keep
public class R8 {

  private final Timing timing = new Timing("R8");
  private final InternalOptions options;

  private R8(InternalOptions options) {
    this.options = options;
    options.itemFactory.resetSortedIndices();
  }

  /**
   * Main API entry for the R8 compiler.
   *
   * <p>The R8 API is intentionally limited and should "do the right thing" given a command. If this
   * API does not suffice please contact the R8 team.
   *
   * @param command R8 command.
   */
  public static void run(com.debughelper.tools.r8.R8Command command) throws com.debughelper.tools.r8.CompilationFailedException {
    AndroidApp app = command.getInputApp();
    InternalOptions options = command.getInternalOptions();
    ExecutorService executor = ThreadUtils.getExecutorService(options);
    ExceptionUtils.withR8CompilationHandler(
        command.getReporter(),
        () -> {
          try {
            run(app, options, executor);
          } finally {
            executor.shutdown();
          }
        });
  }

  /**
   * Main API entry for the R8 compiler.
   *
   * <p>The R8 API is intentionally limited and should "do the right thing" given a command. If this
   * API does not suffice please contact the R8 team.
   *
   * @param command R8 command.
   * @param executor executor service from which to get threads for multi-threaded processing.
   */
  public static void run(com.debughelper.tools.r8.R8Command command, ExecutorService executor)
      throws com.debughelper.tools.r8.CompilationFailedException {
    AndroidApp app = command.getInputApp();
    InternalOptions options = command.getInternalOptions();
    ExceptionUtils.withR8CompilationHandler(
        command.getReporter(),
        () -> {
          run(app, options, executor);
        });
  }

  // Compute the marker to be placed in the main dex file.
  private static Marker getMarker(InternalOptions options) {
    if (options.hasMarker()) {
      return options.getMarker();
    }
    Marker marker = new Marker(Tool.R8)
        .setVersion(com.debughelper.tools.r8.Version.LABEL)
        .setMinApi(options.minApiLevel);
    if (com.debughelper.tools.r8.Version.isDev()) {
      marker.setSha1(VersionProperties.INSTANCE.getSha());
    }
    return marker;
  }

  static void writeApplication(
      ExecutorService executorService,
      DexApplication application,
      String deadCode,
      NamingLens namingLens,
      String proguardSeedsData,
      InternalOptions options,
      ProguardMapSupplier proguardMapSupplier)
      throws ExecutionException {
    try {
      Marker marker = getMarker(options);
      if (options.isGeneratingClassFiles()) {
        new CfApplicationWriter(
                application,
                options,
                deadCode,
                namingLens,
                proguardSeedsData,
                proguardMapSupplier)
            .write(options.getClassFileConsumer(), executorService);
      } else {
        new ApplicationWriter(
                application,
                options,
                marker == null ? null : Collections.singletonList(marker),
                deadCode,
                namingLens,
                proguardSeedsData,
                proguardMapSupplier)
            .write(executorService);
      }
    } catch (IOException e) {
      throw new RuntimeException("Cannot write application", e);
    }
  }

  private Set<DexType> filterMissingClasses(Set<DexType> missingClasses,
      ProguardClassFilter dontWarnPatterns) {
    Set<DexType> result = new HashSet<>(missingClasses);
    dontWarnPatterns.filterOutMatches(result);
    return result;
  }

  static void runForTesting(AndroidApp app, InternalOptions options) throws IOException {
    ExecutorService executor = ThreadUtils.getExecutorService(options);
    try {
      run(app, options, executor);
    } finally {
      executor.shutdown();
    }
  }

  private static void run(AndroidApp app, InternalOptions options, ExecutorService executor)
      throws IOException {
    new R8(options).run(app, executor);
  }

  private void run(AndroidApp inputApp, ExecutorService executorService) throws IOException {
    assert options.programConsumer != null;
    if (options.quiet) {
      System.setOut(new PrintStream(ByteStreams.nullOutputStream()));
    }
    // TODO(b/65390962): Remove this warning once the CF backend is complete.
    if (options.isGeneratingClassFiles()) {
      options.reporter.warning(new StringDiagnostic(
          "R8 support for generating Java classfiles is incomplete and experimental. "
              + "Even if R8 appears to succeed, the generated output is likely incorrect."));
    }
    try {
      AndroidApiLevel oLevel = AndroidApiLevel.O;
      if (options.minApiLevel >= oLevel.getLevel()
          && !options.mainDexKeepRules.isEmpty()) {
        throw new CompilationError("Automatic main dex list is not supported when compiling for "
            + oLevel.getName() + " and later (--min-api " + oLevel.getLevel() + ")");
      }
      DexApplication application =
          new ApplicationReader(inputApp, options, timing).read(executorService).toDirect();

      AppInfoWithSubtyping appInfo = new AppInfoWithSubtyping(application);
      RootSet rootSet;
      String proguardSeedsData = null;
      timing.begin("Strip unused code");
      try {
        Set<DexType> missingClasses = appInfo.getMissingClasses();
        missingClasses = filterMissingClasses(
            missingClasses, options.proguardConfiguration.getDontWarnPatterns());
        if (!missingClasses.isEmpty()) {
          missingClasses.forEach(
              clazz -> {
                options.reporter.warning(
                    new StringDiagnostic("Missing class: " + clazz.toSourceString()));
              });
          if (!options.ignoreMissingClasses) {
            throw new CompilationError(
                "Compilation can't be completed because some library classes are missing.");
          }
        }

        // Compute kotlin info before setting the roots and before
        // kotlin metadata annotation is removed.
        computeKotlinInfoForProgramClasses(application, appInfo);

        final ProguardConfiguration.Builder compatibility =
            ProguardConfiguration.builder(application.dexItemFactory, options.reporter);

        rootSet = new RootSetBuilder(
                    appInfo, application, options.proguardConfiguration.getRules(), options)
                .run(executorService);
        ProtoLiteExtension protoLiteExtension =
            options.forceProguardCompatibility ? null : new ProtoLiteExtension(appInfo);
        Enqueuer enqueuer = new Enqueuer(appInfo, options, options.forceProguardCompatibility,
            compatibility, protoLiteExtension);
        appInfo = enqueuer.traceApplication(rootSet, executorService, timing);
        if (options.proguardConfiguration.isPrintSeeds()) {
          ByteArrayOutputStream bytes = new ByteArrayOutputStream();
          PrintStream out = new PrintStream(bytes);
          RootSetBuilder.writeSeeds(appInfo.withLiveness(), out, type -> true);
          out.flush();
          proguardSeedsData = bytes.toString();
        }
        if (options.enableTreeShaking) {
          TreePruner pruner = new TreePruner(application, appInfo.withLiveness(), options);
          application = pruner.run();
          // Recompute the subtyping information.
          appInfo = appInfo.withLiveness().prunedCopyFrom(application, pruner.getRemovedClasses());
          new AbstractMethodRemover(appInfo).run();
        }
        new AnnotationRemover(appInfo.withLiveness(), compatibility, options).run();

        // TODO(69445518): This is still work in progress, and this file writing is currently used
        // for testing.
        if (options.forceProguardCompatibility
            && options.proguardCompatibilityRulesOutput != null) {
          try (Closer closer = Closer.create()) {
            OutputStream outputStream =
                FileUtils.openPath(
                    closer,
                    options.proguardCompatibilityRulesOutput,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
            PrintStream ps = new PrintStream(outputStream);
            ps.println(compatibility.buildRaw().toString());
          }
        }
      } finally {
        timing.end();
      }

      if (options.proguardConfiguration.isAccessModificationAllowed()) {
        ClassAndMemberPublicizer.run(application, appInfo.dexItemFactory);
        // We can now remove visibility bridges. Note that we do not need to update the
        // invoke-targets here, as the existing invokes will simply dispatch to the now
        // visible super-method. MemberRebinding, if run, will then dispatch it correctly.
        application = new VisibilityBridgeRemover(appInfo, application).run();
      }

      GraphLense graphLense = GraphLense.getIdentityLense();

      if (appInfo.hasLiveness()) {
        graphLense = new MemberRebindingAnalysis(appInfo.withLiveness(), graphLense).run();
        // Class merging requires inlining.
        if (options.enableClassMerging && options.enableInlining) {
          timing.begin("ClassMerger");
          VerticalClassMerger classMerger =
              new VerticalClassMerger(application, appInfo.withLiveness(), graphLense, timing);
          graphLense = classMerger.run();
          timing.end();

          appInfo = appInfo.withLiveness()
              .prunedCopyFrom(application, classMerger.getRemovedClasses());
        }
        if (options.proguardConfiguration.hasApplyMappingFile()) {
          SeedMapper seedMapper =
              SeedMapper.seedMapperFromFile(options.proguardConfiguration.getApplyMappingFile());
          timing.begin("apply-mapping");
          graphLense =
              new ProguardMapApplier(appInfo.withLiveness(), graphLense, seedMapper).run(timing);
          timing.end();
        }
        application = application.asDirect().rewrittenWithLense(graphLense);
        appInfo = appInfo.withLiveness().rewrittenWithLense(application.asDirect(), graphLense);
        // Collect switch maps and ordinals maps.
        appInfo = new SwitchMapCollector(appInfo.withLiveness(), options).run();
        appInfo = new EnumOrdinalMapCollector(appInfo.withLiveness(), options).run();

        // TODO(b/79143143): re-enable once fixed.
        // graphLense = new BridgeMethodAnalysis(graphLense, appInfo.withLiveness()).run();
      }

      timing.begin("Create IR");
      CfgPrinter printer = options.printCfg ? new CfgPrinter() : null;
      try {
        IRConverter converter = new IRConverter(appInfo, options, timing, printer, graphLense);
        application = converter.optimize(application, executorService);
      } finally {
        timing.end();
      }

      if (options.printCfg) {
        if (options.printCfgFile == null || options.printCfgFile.isEmpty()) {
          System.out.print(printer.toString());
        } else {
          try (OutputStreamWriter writer = new OutputStreamWriter(
              new FileOutputStream(options.printCfgFile),
              StandardCharsets.UTF_8)) {
            writer.write(printer.toString());
          }
        }
      }

      // Overwrite SourceFile if specified. This step should be done after IR conversion.
      timing.begin("Rename SourceFile");
      new SourceFileRewriter(appInfo, options).run();
      timing.end();

      if (!options.mainDexKeepRules.isEmpty()) {
        appInfo = new AppInfoWithSubtyping(application);
        Enqueuer enqueuer = new Enqueuer(appInfo, options, true);
        // Lets find classes which may have code executed before secondary dex files installation.
        RootSet mainDexRootSet =
            new RootSetBuilder(appInfo, application, options.mainDexKeepRules, options)
                .run(executorService);
        AppInfoWithLiveness mainDexAppInfo =
            enqueuer.traceMainDex(mainDexRootSet, executorService, timing);

        // LiveTypes is the result.
        Set<DexType> mainDexBaseClasses = new HashSet<>(mainDexAppInfo.liveTypes);

        // Calculate the automatic main dex list according to legacy multidex constraints.
        // Add those classes to an eventual manual list of classes.
        application = application.builder()
            .addToMainDexList(new MainDexListBuilder(mainDexBaseClasses, application).run())
            .build();
      }

      appInfo = new AppInfoWithSubtyping(application);

      if (options.enableTreeShaking || options.enableMinification) {
        timing.begin("Post optimization code stripping");
        try {
          Enqueuer enqueuer = new Enqueuer(appInfo, options, options.forceProguardCompatibility);
          appInfo = enqueuer.traceApplication(rootSet, executorService, timing);
          if (options.enableTreeShaking) {
            TreePruner pruner = new TreePruner(application, appInfo.withLiveness(), options);
            application = pruner.run();
            appInfo = appInfo.withLiveness()
                .prunedCopyFrom(application, pruner.getRemovedClasses());
            // Print reasons on the application after pruning, so that we reflect the actual result.
            ReasonPrinter reasonPrinter = enqueuer.getReasonPrinter(rootSet.reasonAsked);
            reasonPrinter.run(application);
          }
        } finally {
          timing.end();
        }
      }

      // Only perform discard-checking if tree-shaking is turned on.
      if (options.enableTreeShaking && !rootSet.checkDiscarded.isEmpty()) {
        new DiscardedChecker(rootSet, application, options).run();
      }

      timing.begin("Minification");
      // If we did not have keep rules, everything will be marked as keep, so no minification
      // will happen. Just avoid the overhead.
      NamingLens namingLens =
          options.enableMinification
              ? new Minifier(appInfo.withLiveness(), rootSet, options).run(timing)
              : NamingLens.getIdentityLens();
      timing.end();

      ProguardMapSupplier proguardMapSupplier;

      if (options.lineNumberOptimization != LineNumberOptimization.OFF) {
        timing.begin("Line number remapping");
        ClassNameMapper classNameMapper =
            LineNumberOptimizer.run(
                application,
                namingLens,
                options.lineNumberOptimization == LineNumberOptimization.IDENTITY_MAPPING);
        timing.end();
        proguardMapSupplier =
            ProguardMapSupplier.fromClassNameMapper(classNameMapper, options.minApiLevel);
      } else {
        proguardMapSupplier =
            ProguardMapSupplier.fromNamingLens(namingLens, application, options.minApiLevel);
      }

      // If a method filter is present don't produce output since the application is likely partial.
      if (options.hasMethodsFilter()) {
        System.out.println("Finished compilation with method filter: ");
        options.methodsFilter.forEach((m) -> System.out.println("  - " + m));
        return;
      }

      // Generate the resulting application resources.
      writeApplication(
          executorService,
          application,
          application.deadCode,
          namingLens,
          proguardSeedsData,
          options,
          proguardMapSupplier);

      options.printWarnings();
    } catch (ExecutionException e) {
      unwrapExecutionException(e);
      throw new AssertionError(e); // unwrapping method should have thrown
    } finally {
      options.signalFinishedToConsumers();
      // Dump timings.
      if (options.printTimes) {
        timing.report();
      }
    }
  }

  private void computeKotlinInfoForProgramClasses(
      DexApplication application, AppInfoWithSubtyping appInfo) {
    Kotlin kotlin = appInfo.dexItemFactory.kotlin;
    Reporter reporter = options.reporter;
    for (DexProgramClass programClass : application.classes()) {
      programClass.setKotlinInfo(kotlin.getKotlinInfo(programClass, reporter));
    }
  }

  static void unwrapExecutionException(ExecutionException executionException) {
    Throwable cause = executionException.getCause();
    if (cause instanceof CompilationError) {
      // add original exception as suppressed exception to provide the original stack trace
      cause.addSuppressed(executionException);
      throw (CompilationError) cause;
    } else if (cause instanceof RuntimeException) {
      cause.addSuppressed(executionException);
      throw (RuntimeException) cause;
    } else {
      throw new RuntimeException(executionException.getMessage(), cause);
    }
  }

  private static void run(String[] args) throws CompilationFailedException {
    com.debughelper.tools.r8.R8Command command = com.debughelper.tools.r8.R8Command.parse(args, CommandLineOrigin.INSTANCE).build();
    if (command.isPrintHelp()) {
      System.out.println(USAGE_MESSAGE);
      return;
    }
    if (command.isPrintVersion()) {
      Version.printToolVersion("R8");
      return;
    }
    InternalOptions options = command.getInternalOptions();
    ExecutorService executorService = ThreadUtils.getExecutorService(options);
    try {
      ExceptionUtils.withR8CompilationHandler(options.reporter, () ->
          run(command.getInputApp(), options, executorService));
    } finally {
      executorService.shutdown();
    }
  }

  /**
   * Command-line entry to R8.
   *
   * See {@link R8Command#USAGE_MESSAGE} or run {@code r8 --help} for usage information.
   */
  public static void main(String[] args) {
    if (args.length == 0) {
      System.err.println(USAGE_MESSAGE);
      System.exit(ExceptionUtils.STATUS_ERROR);
    }
    ExceptionUtils.withMainProgramHandler(() -> run(args));
  }
}
