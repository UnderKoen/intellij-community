// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package git4idea.annotate;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.NlsContexts;
import com.intellij.openapi.util.NlsSafe;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.util.registry.Registry;
import com.intellij.openapi.vcs.*;
import com.intellij.openapi.vcs.annotate.*;
import com.intellij.openapi.vcs.changes.ContentRevision;
import com.intellij.openapi.vcs.history.VcsFileRevision;
import com.intellij.openapi.vcs.history.VcsRevisionNumber;
import com.intellij.openapi.vcs.impl.AbstractVcsHelperImpl;
import com.intellij.openapi.vcs.versionBrowser.CommittedChangeList;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.concurrency.EdtExecutorService;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.text.DateFormatUtil;
import com.intellij.vcs.log.Hash;
import com.intellij.vcs.log.VcsUser;
import com.intellij.vcs.log.impl.CommonUiProperties;
import com.intellij.vcs.log.impl.HashImpl;
import com.intellij.vcs.log.impl.VcsLogApplicationSettings;
import com.intellij.vcs.log.impl.VcsLogNavigationUtil;
import com.intellij.vcs.log.util.VcsUserUtil;
import com.intellij.vcsUtil.VcsUtil;
import git4idea.*;
import git4idea.changes.GitCommittedChangeList;
import git4idea.changes.GitCommittedChangeListProvider;
import git4idea.log.GitCommitTooltipLinkHandler;
import git4idea.repo.GitRepository;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;

public final class GitFileAnnotation extends FileAnnotation {
  private static final Logger LOG = Logger.getInstance(GitFileAnnotation.class);

  private final Project myProject;
  private final @NotNull VirtualFile myFile;
  private final @NotNull FilePath myFilePath;
  private final @NotNull GitVcs myVcs;
  private final @Nullable VcsRevisionNumber myBaseRevision;

  private final @NotNull List<LineInfo> myLines;
  private @Nullable List<VcsFileRevision> myRevisions;
  private @Nullable Object2IntMap<VcsRevisionNumber> myRevisionMap;
  private final @NotNull Map<VcsRevisionNumber, String> myCommitMessageMap = new HashMap<>();

  private final LineAnnotationAspect DATE_ASPECT =
    new GitAnnotationAspect(LineAnnotationAspect.DATE, VcsBundle.message("line.annotation.aspect.date"), true) {
      @Override
      public String doGetValue(LineInfo info) {
        Date date = getDate(info);
        return FileAnnotation.formatDate(date);
      }
    };

  private final LineAnnotationAspect REVISION_ASPECT =
    new GitAnnotationAspect(LineAnnotationAspect.REVISION, VcsBundle.message("line.annotation.aspect.revision"), false) {
      @Override
      protected String doGetValue(LineInfo lineInfo) {
        return lineInfo.getRevisionNumber().getShortRev();
      }
    };

  private final LineAnnotationAspect AUTHOR_ASPECT =
    new GitAnnotationAspect(LineAnnotationAspect.AUTHOR, VcsBundle.message("line.annotation.aspect.author"), true) {
      @Override
      protected String doGetValue(LineInfo lineInfo) {
        return VcsUserUtil.toExactString(lineInfo.getAuthorUser());
      }
    };

  public GitFileAnnotation(@NotNull Project project,
                           @NotNull VirtualFile file,
                           @Nullable VcsRevisionNumber revision,
                           @NotNull List<LineInfo> lines) {
    super(project);
    myProject = project;
    myFile = file;
    myFilePath = VcsUtil.getFilePath(file);
    myVcs = GitVcs.getInstance(myProject);
    myBaseRevision = revision;
    myLines = lines;
  }

  @Override
  public LineAnnotationAspect @NotNull [] getAspects() {
    return new LineAnnotationAspect[]{REVISION_ASPECT, DATE_ASPECT, AUTHOR_ASPECT};
  }

  private static @NotNull Date getDate(LineInfo info) {
    VcsLogApplicationSettings logSettings = ApplicationManager.getApplication().getService(VcsLogApplicationSettings.class);
    return Boolean.TRUE.equals(logSettings.get(CommonUiProperties.PREFER_COMMIT_DATE)) ? info.getCommitterDate() : info.getAuthorDate();
  }

  @Override
  public @Nullable String getAnnotatedContent() {
    try {
      ContentRevision revision = GitContentRevision.createRevision(myFilePath, myBaseRevision, myProject);
      return revision.getContent();
    }
    catch (VcsException e) {
      return null;
    }
  }

  @Override
  public @Nullable List<VcsFileRevision> getRevisions() {
    return myRevisions;
  }

  public void setRevisions(@NotNull List<VcsFileRevision> revisions) {
    myRevisions = revisions;

    myRevisionMap = new Object2IntOpenHashMap<>();
    for (int i = 0; i < myRevisions.size(); i++) {
      myRevisionMap.put(myRevisions.get(i).getRevisionNumber(), i);
    }
  }

  public void setCommitMessage(@NotNull VcsRevisionNumber revisionNumber, @NotNull String message) {
    myCommitMessageMap.put(revisionNumber, message);
  }

  @Override
  public int getLineCount() {
    return myLines.size();
  }

  public @Nullable LineInfo getLineInfo(int lineNumber) {
    if (lineNumberCheck(lineNumber)) return null;
    return myLines.get(lineNumber);
  }

  @Override
  public @NlsContexts.Tooltip @Nullable String getToolTip(int lineNumber) {
    return getToolTip(lineNumber, false);
  }

  @Override
  public @NlsContexts.Tooltip @Nullable String getHtmlToolTip(int lineNumber) {
    return getToolTip(lineNumber, true);
  }

  private @NlsContexts.Tooltip @Nullable String getToolTip(int lineNumber, boolean asHtml) {
    LineInfo lineInfo = getLineInfo(lineNumber);
    if (lineInfo == null) return null;

    AnnotationTooltipBuilder atb = new AnnotationTooltipBuilder(myProject, asHtml);
    GitRevisionNumber revisionNumber = lineInfo.getRevisionNumber();

    atb.appendRevisionLine(revisionNumber, it -> GitCommitTooltipLinkHandler.createLink(it.asString(), it));
    atb.appendLine(VcsBundle.message("commit.description.tooltip.author", VcsUserUtil.toExactString(lineInfo.getAuthorUser())));
    atb.appendLine(VcsBundle.message("commit.description.tooltip.date", DateFormatUtil.formatDateTime(getDate(lineInfo))));

    if (!myFilePath.equals(lineInfo.getFilePath())) {
      String path = VcsUtil.getPresentablePath(myProject, lineInfo.getFilePath(), true, false);
      atb.appendLine(VcsBundle.message("commit.description.tooltip.path", path));
    }

    String commitMessage = getCommitMessage(revisionNumber);
    if (commitMessage == null) commitMessage = lineInfo.getSubject() + "\n...";
    atb.appendCommitMessageBlock(commitMessage);

    return atb.toString();
  }

  public @NlsSafe @Nullable String getCommitMessage(@NotNull VcsRevisionNumber revisionNumber) {
    if (myRevisions != null && myRevisionMap != null &&
        myRevisionMap.containsKey(revisionNumber)) {
      VcsFileRevision fileRevision = myRevisions.get(myRevisionMap.getInt(revisionNumber));
      return fileRevision.getCommitMessage();
    }
    return myCommitMessageMap.get(revisionNumber);
  }

  @Override
  public @Nullable VcsRevisionNumber getLineRevisionNumber(int lineNumber) {
    LineInfo lineInfo = getLineInfo(lineNumber);
    return lineInfo != null ? lineInfo.getRevisionNumber() : null;
  }

  @Override
  public @Nullable Date getLineDate(int lineNumber) {
    LineInfo lineInfo = getLineInfo(lineNumber);
    return lineInfo != null ? getDate(lineInfo) : null;
  }

  private boolean lineNumberCheck(int lineNumber) {
    return myLines.size() <= lineNumber || lineNumber < 0;
  }

  public @NotNull List<LineInfo> getLines() {
    return myLines;
  }

  /**
   * Revision annotation aspect implementation
   */
  private abstract class GitAnnotationAspect extends LineAnnotationAspectAdapter {
    GitAnnotationAspect(@NonNls String id, @NlsContexts.ListItem String displayName, boolean showByDefault) {
      super(id, displayName, showByDefault);
    }

    @Override
    public String getValue(int lineNumber) {
      if (lineNumberCheck(lineNumber)) {
        return "";
      }
      else {
        return doGetValue(myLines.get(lineNumber));
      }
    }

    protected abstract String doGetValue(LineInfo lineInfo);

    @Override
    protected void showAffectedPaths(int lineNum) {
      if (lineNum >= 0 && lineNum < myLines.size()) {
        LineInfo info = myLines.get(lineNum);

        VirtualFile root = ProjectLevelVcsManager.getInstance(myProject).getVcsRootFor(myFilePath);
        if (root == null) return;

        CompletableFuture<Boolean> shownInLog;
        if (ModalityState.current() == ModalityState.nonModal() &&
            Registry.is("vcs.blame.show.affected.files.in.log")) {
          Hash hash = HashImpl.build(info.getRevisionNumber().asString());
          shownInLog = VcsLogNavigationUtil.jumpToRevisionAsync(myProject, root, hash, info.getFilePath());
        }
        else {
          shownInLog = CompletableFuture.completedFuture(false); // can't use log tabs in modal dialogs (ex: commit, merge)
        }
        shownInLog.whenCompleteAsync((success, ex) -> {
          if (ex instanceof CancellationException) return;
          if (ex != null) {
            LOG.error(ex);
          }
          if (!Boolean.TRUE.equals(success)) {
            AbstractVcsHelperImpl.loadAndShowCommittedChangesDetails(myProject, info.getRevisionNumber(), myFilePath, false,
                                                                     () -> getRevisionsChangesProvider().getChangesIn(lineNum));
          }
        }, EdtExecutorService.getInstance());
      }
    }
  }

  public static class CommitInfo {
    private final @NotNull Project myProject;
    private final @NotNull GitRevisionNumber myRevision;
    private final @NotNull FilePath myFilePath;
    private final @Nullable GitRevisionNumber myPreviousRevision;
    private final @Nullable FilePath myPreviousFilePath;
    private final @NotNull Date myCommitterDate;
    private final @NotNull Date myAuthorDate;
    private final @NotNull VcsUser myAuthor;
    private final @NotNull @NlsSafe String mySubject;

    public CommitInfo(@NotNull Project project,
               @NotNull GitRevisionNumber revision,
               @NotNull FilePath path,
               @NotNull Date committerDate,
               @NotNull Date authorDate,
               @NotNull VcsUser author,
               @NotNull @NlsSafe String subject,
               @Nullable GitRevisionNumber previousRevision,
               @Nullable FilePath previousPath) {
      myProject = project;
      myRevision = revision;
      myFilePath = path;
      myPreviousRevision = previousRevision;
      myPreviousFilePath = previousPath;
      myCommitterDate = committerDate;
      myAuthorDate = authorDate;
      myAuthor = author;
      mySubject = subject;
    }

    public @NotNull GitRevisionNumber getRevisionNumber() {
      return myRevision;
    }

    public @NotNull FilePath getFilePath() {
      return myFilePath;
    }

    public @NotNull VcsFileRevision getFileRevision() {
      return new GitFileRevision(myProject, myFilePath, myRevision);
    }

    public @Nullable VcsFileRevision getPreviousFileRevision() {
      if (myPreviousRevision == null || myPreviousFilePath == null) return null;
      return new GitFileRevision(myProject, myPreviousFilePath, myPreviousRevision);
    }

    public @NotNull Date getCommitterDate() {
      return myCommitterDate;
    }

    public @NotNull Date getAuthorDate() {
      return myAuthorDate;
    }

    public @NotNull @Nls String getAuthor() {
      return myAuthor.getName();
    }

    public @NotNull VcsUser getAuthorUser() {
      return myAuthor;
    }

    public @NotNull @Nls String getSubject() {
      return mySubject;
    }
  }

  public static class LineInfo {
    private final @NotNull CommitInfo myCommitInfo;
    private final int myLineNumber;
    private final int myOriginalLineNumber;

    public LineInfo(@NotNull CommitInfo commitInfo, int lineNumber, int originalLineNumber) {
      this.myCommitInfo = commitInfo;
      this.myLineNumber = lineNumber;
      this.myOriginalLineNumber = originalLineNumber;
    }

    public int getLineNumber() {
      return myLineNumber;
    }

    public int getOriginalLineNumber() {
      return myOriginalLineNumber;
    }

    public @NotNull GitRevisionNumber getRevisionNumber() {
      return myCommitInfo.getRevisionNumber();
    }

    public @NotNull FilePath getFilePath() {
      return myCommitInfo.getFilePath();
    }

    public @NotNull VcsFileRevision getFileRevision() {
      return myCommitInfo.getFileRevision();
    }

    public @Nullable VcsFileRevision getPreviousFileRevision() {
      return myCommitInfo.getPreviousFileRevision();
    }

    public @NotNull Date getCommitterDate() {
      return myCommitInfo.getCommitterDate();
    }

    public @NotNull Date getAuthorDate() {
      return myCommitInfo.getAuthorDate();
    }

    public @NotNull @Nls String getAuthor() {
      return myCommitInfo.getAuthor();
    }

    public @NotNull VcsUser getAuthorUser() {
      return myCommitInfo.getAuthorUser();
    }

    public @NlsSafe @NotNull String getSubject() {
      return myCommitInfo.getSubject();
    }
  }

  @Override
  public @NotNull VirtualFile getFile() {
    return myFile;
  }

  @Override
  public @Nullable VcsRevisionNumber getCurrentRevision() {
    return myBaseRevision;
  }

  @Override
  public VcsKey getVcsKey() {
    return GitVcs.getKey();
  }

  @Override
  public boolean isBaseRevisionChanged(@NotNull VcsRevisionNumber number) {
    if (!myFile.isInLocalFileSystem()) return false;
    final VcsRevisionNumber currentCurrentRevision = myVcs.getDiffProvider().getCurrentRevision(myFile);
    return myBaseRevision != null && !myBaseRevision.equals(currentCurrentRevision);
  }


  @Override
  public @NotNull CurrentFileRevisionProvider getCurrentFileRevisionProvider() {
    return new GitCurrentFileRevisionProvider();
  }

  @Override
  public @NotNull PreviousFileRevisionProvider getPreviousFileRevisionProvider() {
    return new GitPreviousFileRevisionProvider();
  }

  @Override
  public @NotNull AuthorsMappingProvider getAuthorsMappingProvider() {
    return new GitAuthorsMappingProvider();
  }

  @Override
  public @NotNull RevisionsOrderProvider getRevisionsOrderProvider() {
    return new GitRevisionsOrderProvider();
  }

  @Override
  public @NotNull RevisionChangesProvider getRevisionsChangesProvider() {
    return new GitRevisionChangesProvider();
  }

  @Override
  public @NotNull LineModificationDetailsProvider getLineModificationDetailsProvider() {
    return new GitLineModificationDetailsProvider();
  }


  private class GitCurrentFileRevisionProvider implements CurrentFileRevisionProvider {
    @Override
    public @Nullable VcsFileRevision getRevision(int lineNumber) {
      LineInfo lineInfo = getLineInfo(lineNumber);
      return lineInfo != null ? lineInfo.getFileRevision() : null;
    }
  }

  private class GitPreviousFileRevisionProvider implements PreviousFileRevisionProvider {
    @Override
    public @Nullable VcsFileRevision getPreviousRevision(int lineNumber) {
      LineInfo lineInfo = getLineInfo(lineNumber);
      if (lineInfo == null) return null;

      VcsFileRevision previousFileRevision = lineInfo.getPreviousFileRevision();
      if (previousFileRevision != null) return previousFileRevision;

      GitRevisionNumber revisionNumber = lineInfo.getRevisionNumber();
      if (myRevisions != null && myRevisionMap != null &&
          myRevisionMap.containsKey(revisionNumber)) {
        int index = myRevisionMap.getInt(revisionNumber);
        if (index + 1 < myRevisions.size()) {
          return myRevisions.get(index + 1);
        }
      }

      return null;
    }

    @Override
    public @Nullable VcsFileRevision getLastRevision() {
      if (myBaseRevision instanceof GitRevisionNumber) {
        return new GitFileRevision(myProject, myFilePath, (GitRevisionNumber)myBaseRevision);
      }
      else {
        return ContainerUtil.getFirstItem(getRevisions());
      }
    }
  }

  private class GitAuthorsMappingProvider implements AuthorsMappingProvider {
    private final Map<VcsRevisionNumber, String> myAuthorsMap = new HashMap<>();

    GitAuthorsMappingProvider() {
      for (int i = 0; i < getLineCount(); i++) {
        LineInfo lineInfo = getLineInfo(i);
        if (lineInfo == null) continue;

        if (!myAuthorsMap.containsKey(lineInfo.getRevisionNumber())) {
          myAuthorsMap.put(lineInfo.getRevisionNumber(), lineInfo.getAuthor());
        }
      }
    }

    @Override
    public @NotNull Map<VcsRevisionNumber, String> getAuthors() {
      return myAuthorsMap;
    }
  }

  private class GitRevisionsOrderProvider implements RevisionsOrderProvider {
    private final List<List<VcsRevisionNumber>> myOrderedRevisions = new ArrayList<>();

    GitRevisionsOrderProvider() {
      ContainerUtil.KeyOrderedMultiMap<Date, VcsRevisionNumber> dates = new ContainerUtil.KeyOrderedMultiMap<>();

      for (int i = 0; i < getLineCount(); i++) {
        LineInfo lineInfo = getLineInfo(i);
        if (lineInfo == null) continue;

        VcsRevisionNumber number = lineInfo.getRevisionNumber();
        Date date = lineInfo.getCommitterDate();

        dates.putValue(date, number);
      }

      NavigableSet<Date> orderedDates = dates.navigableKeySet();
      for (Date date : orderedDates.descendingSet()) {
        Collection<VcsRevisionNumber> revisionNumbers = dates.get(date);
        myOrderedRevisions.add(new ArrayList<>(revisionNumbers));
      }
    }

    @Override
    public @NotNull List<List<VcsRevisionNumber>> getOrderedRevisions() {
      return myOrderedRevisions;
    }
  }

  private class GitRevisionChangesProvider implements RevisionChangesProvider {
    @Override
    public @NotNull Pair<? extends CommittedChangeList, FilePath> getChangesIn(int lineNumber) throws VcsException {
      LineInfo lineInfo = getLineInfo(lineNumber);
      if (lineInfo == null) {
        throw new IllegalArgumentException(VcsBundle.message("error.annotated.line.out.of.bounds", lineNumber, getLineCount()));
      }

      GitRepository repository = GitUtil.getRepositoryForFile(myProject, lineInfo.getFilePath());

      // Do not use CommittedChangesProvider#getOneList to avoid unnecessary rename detections (as we know FilePath already).
      GitCommittedChangeList changeList =
        GitCommittedChangeListProvider.getCommittedChangeList(myProject, repository.getRoot(), lineInfo.getRevisionNumber());
      return Pair.create(changeList, lineInfo.getFilePath());
    }
  }

  private class GitLineModificationDetailsProvider implements LineModificationDetailsProvider {
    @Override
    public @Nullable AnnotatedLineModificationDetails getDetails(int lineNumber) throws VcsException {
      LineInfo lineInfo = getLineInfo(lineNumber);
      if (lineInfo == null) return null;

      String afterContent = DefaultLineModificationDetailsProvider.loadRevision(myProject, lineInfo.getFileRevision(), myFilePath);
      if (afterContent == null) return null;

      String beforeContent = DefaultLineModificationDetailsProvider.loadRevision(myProject, lineInfo.getPreviousFileRevision(), myFilePath);

      int originalLineNumber = lineInfo.getOriginalLineNumber() - 1;
      return DefaultLineModificationDetailsProvider.createDetailsFor(beforeContent, afterContent, originalLineNumber);
    }
  }
}
