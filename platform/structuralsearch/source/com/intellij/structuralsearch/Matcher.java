// Copyright 2000-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.structuralsearch;

import com.intellij.dupLocator.iterators.ArrayBackedNodeIterator;
import com.intellij.dupLocator.iterators.NodeIterator;
import com.intellij.lang.Language;
import com.intellij.lang.injection.InjectedLanguageManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.fileTypes.FileTypes;
import com.intellij.openapi.fileTypes.LanguageFileType;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ContentIterator;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.*;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.LocalSearchScope;
import com.intellij.psi.search.SearchScope;
import com.intellij.structuralsearch.impl.matcher.*;
import com.intellij.structuralsearch.impl.matcher.compiler.PatternCompiler;
import com.intellij.structuralsearch.impl.matcher.handlers.MatchingHandler;
import com.intellij.structuralsearch.impl.matcher.handlers.TopLevelMatchingHandler;
import com.intellij.structuralsearch.impl.matcher.iterators.SsrFilteringNodeIterator;
import com.intellij.structuralsearch.impl.matcher.strategies.MatchingStrategy;
import com.intellij.structuralsearch.plugin.ui.Configuration;
import com.intellij.structuralsearch.plugin.ui.ConfigurationManager;
import com.intellij.structuralsearch.plugin.util.CollectingMatchResultSink;
import com.intellij.structuralsearch.plugin.util.DuplicateFilteringResultSink;
import com.intellij.util.IncorrectOperationException;
import com.intellij.util.PairProcessor;
import com.intellij.util.SmartList;
import com.intellij.util.indexing.FileBasedIndex;
import org.jetbrains.annotations.NotNull;

import java.util.*;

import static com.intellij.structuralsearch.impl.matcher.iterators.SingleNodeIterator.newSingleNodeIterator;

/**
 * This class makes program structure tree matching:
 */
public class Matcher {
  static final Logger LOG = Logger.getInstance("#com.intellij.structuralsearch.impl.matcher.MatcherImpl");

  @SuppressWarnings("SSBasedInspection")
  private static final ThreadLocal<Set<String>> ourRecursionGuard = ThreadLocal.withInitial(() -> new HashSet<>());

  // project being worked on
  final Project project;
  final DumbService myDumbService;

  // context of matching
  final MatchContext matchContext;
  private boolean isTesting;

  // visitor to delegate the real work
  private final GlobalMatchingVisitor visitor = new GlobalMatchingVisitor();
  ProgressIndicator progress;
  private final TaskScheduler scheduler = new TaskScheduler();

  int totalFilesToScan;
  int scannedFilesCount;

  public Matcher(Project project) {
    this(project, null);
  }

  public Matcher(final Project project, final MatchOptions matchOptions) {
    this.project = project;
    matchContext = new MatchContext();
    matchContext.setMatcher(visitor);

    if (matchOptions != null) {
      matchContext.setOptions(matchOptions);
      matchContext.setPattern(PatternCompiler.compilePattern(project, matchOptions));
    }
    myDumbService = DumbService.getInstance(project);
  }

  public static Matcher buildMatcher(Project project, FileType fileType, String constraint) {
    if (StringUtil.isQuotedString(constraint)) {
      // keep old configurations working, also useful for testing
      final MatchOptions myMatchOptions = new MatchOptions();
      myMatchOptions.setFileType(fileType);
      myMatchOptions.fillSearchCriteria(StringUtil.unquoteString(constraint));
      return new Matcher(project, myMatchOptions);
    }
    else {
      final Set<String> set = ourRecursionGuard.get();
      if (!set.add(constraint)) {
        throw new MalformedPatternException("Pattern recursively references itself");
      }
      try {
        final Configuration configuration = ConfigurationManager.getInstance(project).findConfigurationByName(constraint);
        if (configuration == null) {
          throw new MalformedPatternException("Configuration '" + constraint + "' not found");
        }
        return new Matcher(project, configuration.getMatchOptions());
      } finally {
        set.remove(constraint);
        if (set.isEmpty()) {
          // we're finished with this thread local
          ourRecursionGuard.remove();
        }
      }
    }
  }

  public static void validate(Project project, MatchOptions options) {
    PatternCompiler.compilePattern(project, options);
  }

  public static boolean checkIfShouldAttemptToMatch(MatchContext context, NodeIterator matchedNodes) {
    final CompiledPattern pattern = context.getPattern();
    final NodeIterator patternNodes = pattern.getNodes();
    try {
      while (true) {
        final PsiElement patternNode = patternNodes.current();
        if (patternNode == null) {
          return true;
        }
        final PsiElement matchedNode = matchedNodes.current();
        if (matchedNode == null) {
          return false;
        }
        final MatchingHandler matchingHandler = pattern.getHandler(patternNode);
        if (matchingHandler == null || !matchingHandler.canMatch(patternNode, matchedNode, context)) {
          return false;
        }
        matchedNodes.advance();
        patternNodes.advance();
      }
    } finally {
      patternNodes.reset();
      matchedNodes.reset();
    }
  }

  public void processMatchesInElement(MatchContext context,
                                      Configuration configuration,
                                      NodeIterator matchedNodes,
                                      PairProcessor<MatchResult, Configuration> processor) {
    try {
      configureOptions(context, configuration, matchedNodes.current(), processor);
      context.setShouldRecursivelyMatch(false);
      visitor.matchContext(matchedNodes);
    } finally {
      matchedNodes.reset();
      context.getOptions().setScope(null);
    }
  }

  public boolean matchNode(@NotNull PsiElement element) {
    final CollectingMatchResultSink sink = new CollectingMatchResultSink();
    final MatchOptions options = matchContext.getOptions();
    final CompiledPattern compiledPattern = prepareMatching(sink, options);
    if (compiledPattern == null) {
      return false;
    }
    matchContext.setShouldRecursivelyMatch(false);
    visitor.matchContext(newSingleNodeIterator(element));
    return !sink.getMatches().isEmpty();
  }

  public void clearContext() {
    matchContext.clear();
  }

  private void configureOptions(MatchContext context,
                                final Configuration configuration,
                                PsiElement psiFile,
                                final PairProcessor<MatchResult, Configuration> processor) {
    if (psiFile == null) return;
    matchContext.clear();
    matchContext.setMatcher(visitor);

    MatchOptions options = context.getOptions();
    matchContext.setOptions(options);
    matchContext.setPattern(context.getPattern());
    matchContext.setShouldRecursivelyMatch(context.shouldRecursivelyMatch());
    visitor.setMatchContext(matchContext);

    matchContext.setSink(
      new DuplicateFilteringResultSink(
        new DefaultMatchResultSink() {
          @Override
          public void newMatch(MatchResult result) {
            processor.process(result, configuration);
          }
        }
      )
    );
  }

  public void precompileOptions(List<Configuration> configurations, Map<Configuration, MatchContext> out) {
    for (final Configuration configuration : configurations) {
      if (out.containsKey(configuration)) {
        continue;
      }
      final MatchContext matchContext = new MatchContext();
      matchContext.setMatcher(visitor);
      final MatchOptions matchOptions = configuration.getMatchOptions();
      matchContext.setOptions(matchOptions);

      try {
        matchContext.setPattern(PatternCompiler.compilePattern(project, matchOptions));
        out.put(configuration, matchContext);
      }
      catch (StructuralSearchException e) {
        LOG.warn("Malformed structural search inspection pattern \"" + configuration.getName() + '"', e);
        out.put(configuration, null);
      }
    }
  }

  /**
   * Finds the matches of given pattern starting from given tree element.
   */
  public void findMatches(MatchResultSink sink, MatchOptions options) throws MalformedPatternException, UnsupportedPatternException {
    CompiledPattern compiledPattern = prepareMatching(sink, options);
    if (compiledPattern == null) {
      return;
    }

    matchContext.getSink().setMatchingProcess( scheduler );
    scheduler.init();
    progress = matchContext.getSink().getProgressIndicator();

    if (isTesting) {
      // testing mode;
      final PsiElement[] elements = ((LocalSearchScope)options.getScope()).getScope();

      PsiElement parent = elements[0].getParent();
      if (matchContext.getPattern().getStrategy().continueMatching(parent != null ? parent : elements[0])) {
        visitor.matchContext(new SsrFilteringNodeIterator(new ArrayBackedNodeIterator(elements)));
      }
      else {
        final LanguageFileType fileType = (LanguageFileType)matchContext.getOptions().getFileType();
        final Language language = fileType.getLanguage();
        for (PsiElement element : elements) {
          match(element, language);
        }
      }

      matchContext.getSink().matchingFinished();
      return;
    }
    if (!findMatches(options, compiledPattern)) {
      return;
    }

    if (scheduler.getTaskQueueEndAction()==null) {
      scheduler.setTaskQueueEndAction(
        () -> matchContext.getSink().matchingFinished()
      );
    }

    scheduler.executeNext();
  }

  private boolean findMatches(MatchOptions options, CompiledPattern compiledPattern) {
    SearchScope searchScope = compiledPattern.getScope();
    final boolean ourOptimizedScope = searchScope != null;
    if (!ourOptimizedScope) searchScope = options.getScope();

    if (searchScope instanceof GlobalSearchScope) {
      final GlobalSearchScope scope = (GlobalSearchScope)searchScope;

      final ContentIterator ci = fileOrDir -> {
        if (!fileOrDir.isDirectory() && scope.contains(fileOrDir) && fileOrDir.getFileType() != FileTypes.UNKNOWN) {
          ++totalFilesToScan;
          scheduler.addOneTask(new MatchOneVirtualFile(fileOrDir));
        }
        return true;
      };

      ReadAction.run(() -> FileBasedIndex.getInstance().iterateIndexableFiles(ci, project, progress));
      progress.setText2("");
    }
    else {
      final PsiElement[] elementsToScan = ((LocalSearchScope)searchScope).getScope();
      totalFilesToScan = elementsToScan.length;

      for (int i = 0; i < elementsToScan.length; ++i) {
        final PsiElement psiElement = elementsToScan[i];

        if (psiElement == null) continue;
        scheduler.addOneTask(new MatchOnePsiFile(psiElement));
        if (ourOptimizedScope) elementsToScan[i] = null; // to prevent long PsiElement reference
      }
    }
    return true;
  }

  private CompiledPattern prepareMatching(final MatchResultSink sink, final MatchOptions options) {
    matchContext.clear();
    matchContext.setSink(new DuplicateFilteringResultSink(sink));
    matchContext.setOptions(options);
    matchContext.setMatcher(visitor);
    matchContext.setPattern(PatternCompiler.compilePattern(project, options));
    visitor.setMatchContext(matchContext);

    return matchContext.getPattern();
  }

  /**
   * Finds the matches of given pattern starting from given tree element.
   * @param source string for search
   * @return list of matches found
   * @throws MalformedPatternException
   * @throws UnsupportedPatternException
   */
  public List<MatchResult> testFindMatches(String source,
                                              MatchOptions options,
                                              boolean fileContext,
                                              FileType sourceFileType,
                                              String sourceExtension,
                                              boolean physicalSourceFile)
    throws MalformedPatternException, UnsupportedPatternException {

    CollectingMatchResultSink sink = new CollectingMatchResultSink();

    try {
      PsiElement[] elements = MatcherImplUtil.createSourceTreeFromText(source,
                                                                       fileContext ? PatternTreeContext.File : PatternTreeContext.Block,
                                                                       sourceFileType,
                                                                       sourceExtension,
                                                                       project, physicalSourceFile);

      options.setScope(new LocalSearchScope(elements));
      testFindMatches(sink, options);
    }
    catch (IncorrectOperationException e) {
      MalformedPatternException exception = new MalformedPatternException();
      exception.initCause(e);
      throw exception;
    } finally {
      options.setScope(null);
    }

    return sink.getMatches();
  }

  public List<MatchResult> testFindMatches(String source, MatchOptions options, boolean fileContext)
    throws MalformedPatternException, UnsupportedPatternException {
    return testFindMatches(source, options, fileContext, options.getFileType(), null, false);
  }

  /**
   * Finds the matches of given pattern starting from given tree element.
   * @param sink match result destination
   * @throws MalformedPatternException
   * @throws UnsupportedPatternException
   */
  public void testFindMatches(MatchResultSink sink, MatchOptions options)
    throws MalformedPatternException, UnsupportedPatternException {
    isTesting = true;
    try {
      findMatches(sink, options);
    } finally {
      isTesting = false;
    }
  }

  class TaskScheduler implements MatchingProcess {
    private ArrayList<Runnable> tasks = new ArrayList<>();
    private boolean ended;
    private Runnable taskQueueEndAction;

    private boolean suspended;

    @Override
    public void stop() {
      ended = true;
    }

    @Override
    public void pause() {
      suspended = true;
    }

    @Override
    public void resume() {
      if (!suspended) return;
      suspended = false;
      executeNext();
    }

    @Override
    public boolean isSuspended() {
      return suspended;
    }

    @Override
    public boolean isEnded() {
      return ended;
    }

    void setTaskQueueEndAction(Runnable taskQueueEndAction) {
      this.taskQueueEndAction = taskQueueEndAction;
    }
    Runnable getTaskQueueEndAction () {
      return taskQueueEndAction;
    }

    void addOneTask(Runnable runnable) {
      tasks.add(runnable);
    }

    void executeNext() {
      while(!suspended && !ended) {
        if (tasks.isEmpty()) {
          ended = true;
          break;
        }

        final Runnable task = tasks.remove(tasks.size() - 1);
        try {
          task.run();
        }
        catch (ProcessCanceledException | StructuralSearchException e) {
          ended = true;
          clearSchedule();
          throw e;
        }
        catch (Throwable th) {
          LOG.error(th);
        }
      }

      if (ended) clearSchedule();
    }

    void init() {
      ended = false;
      suspended = false;
      PsiManager.getInstance(project).startBatchFilesProcessingMode();
    }

    private void clearSchedule() {
      if (tasks != null) {
        taskQueueEndAction.run();
        if (!project.isDisposed()) {
          PsiManager.getInstance(project).finishBatchFilesProcessingMode();
        }
        tasks = null;
      }
    }

  }

  /**
   * Initiates the matching process for given element
   * @param element the current search tree element
   */
  void match(@NotNull PsiElement element, final Language language) {
    final MatchingStrategy strategy = matchContext.getPattern().getStrategy();

    final Language elementLanguage = element.getLanguage();
    if (strategy.continueMatching(element) && elementLanguage.isKindOf(language)) {
      visitor.matchContext(newSingleNodeIterator(element));
      return;
    }
    for(PsiElement el=element.getFirstChild();el!=null;el=el.getNextSibling()) {
      match(el, language);
    }
    if (element instanceof PsiLanguageInjectionHost) {
      InjectedLanguageManager.getInstance(project).enumerate(element, (injectedPsi, places) -> match(injectedPsi, language));
    }
  }

  /**
   * Tests if given element is matched by given pattern starting from target variable.
   * @throws MalformedPatternException
   * @throws UnsupportedPatternException
   */
  @NotNull
  public List<MatchResult> matchByDownUp(PsiElement element) throws MalformedPatternException, UnsupportedPatternException {
    final CollectingMatchResultSink sink = new CollectingMatchResultSink();
    final MatchOptions options = matchContext.getOptions();
    final CompiledPattern compiledPattern = prepareMatching(sink, options);
    matchContext.setShouldRecursivelyMatch(false);

    PsiElement targetNode = compiledPattern.getTargetNode();
    PsiElement elementToStartMatching = null;

    if (targetNode == null) {
      targetNode = compiledPattern.getNodes().current();
      if (targetNode != null) {
        compiledPattern.getNodes().advance();
        assert !compiledPattern.getNodes().hasNext();
        compiledPattern.getNodes().rewind();

        element = element.getParent();
        if (element == null) {
          return Collections.emptyList();
        }
        while (element.getClass() != targetNode.getClass()) {
          element = element.getParent();
          if (element == null)  return Collections.emptyList();
        }

        elementToStartMatching = element;
      }
    } else {
      final StructuralSearchProfile profile = StructuralSearchUtil.getProfileByPsiElement(element);
      if (profile == null) return Collections.emptyList();
      targetNode = profile.extendMatchedByDownUp(targetNode);

      MatchingHandler handler = null;

      while (element.getClass() == targetNode.getClass() ||
             compiledPattern.isTypedVar(targetNode) && compiledPattern.getHandler(targetNode).canMatch(targetNode, element, matchContext)) {
        handler = compiledPattern.getHandler(targetNode);
        handler.setPinnedElement(element);
        elementToStartMatching = element;
        if (handler instanceof TopLevelMatchingHandler) break;
        element = element.getParent();
        targetNode = targetNode.getParent();

        if (options.isLooseMatching()) {
          element = profile.updateCurrentNode(element);
          targetNode = profile.updateCurrentNode(targetNode);
        }
      }

      if (!(handler instanceof TopLevelMatchingHandler)) return Collections.emptyList();
    }

    assert targetNode != null : "Could not match down up when no target node";

    visitor.matchContext(newSingleNodeIterator(elementToStartMatching));
    matchContext.getSink().matchingFinished();
    return sink.getMatches();
  }

  private class MatchOnePsiFile extends MatchOneFile {
    private PsiElement file;

    MatchOnePsiFile(PsiElement file) {
      this.file = file;
    }

    @NotNull
    @Override
    protected List<PsiElement> getPsiElementsToProcess() {
      final PsiElement file = this.file;
      this.file = null;
      return new SmartList<>(file);
    }
  }

  private class MatchOneVirtualFile extends MatchOneFile {
    private final VirtualFile myFile;

    public MatchOneVirtualFile(VirtualFile file) {
      myFile = file;
    }

    @NotNull
    @Override
    protected List<PsiElement> getPsiElementsToProcess() {
      return ReadAction.compute(
        () -> {
          if (!myFile.isValid()) {
            // file may be been deleted since search started
            return Collections.emptyList();
          }
          final PsiFile file = PsiManager.getInstance(project).findFile(myFile);
          if (file == null) {
            return Collections.emptyList();
          }

          final FileViewProvider viewProvider = file.getViewProvider();
          final List<PsiElement> elementsToProcess = new SmartList<>();

          for (Language lang : viewProvider.getLanguages()) {
            elementsToProcess.add(viewProvider.getPsi(lang));
          }

          return elementsToProcess;
        }
      );
    }
  }

  private abstract class MatchOneFile implements Runnable {
    @Override
    public void run() {
      final List<PsiElement> files = getPsiElementsToProcess();

      if (progress!=null) {
        progress.setFraction((double)scannedFilesCount/totalFilesToScan);
      }

      ++scannedFilesCount;

      if (files.isEmpty()) return;

      final LanguageFileType fileType = (LanguageFileType)matchContext.getOptions().getFileType();
      final Language patternLanguage = fileType.getLanguage();
      for (final PsiElement file : files) {
        if (file instanceof PsiFile) {
          matchContext.getSink().processFile((PsiFile)file);
        }

        myDumbService.runReadActionInSmartMode(
          () -> {
            if (!file.isValid()) return;
            final StructuralSearchProfile profile = StructuralSearchUtil.getProfileByLanguage(file.getLanguage());
            if (profile == null) {
              return;
            }
            match(profile.extendMatchOnePsiFile(file), patternLanguage);
          }
        );
      }
    }

    @NotNull
    protected abstract List<PsiElement> getPsiElementsToProcess();
  }
}
