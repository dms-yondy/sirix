/**
 * Copyright (c) 2011, University of Konstanz, Distributed Systems Group All rights reserved.
 * <p>
 * Redistribution and use in source and binary forms, with or without modification, are permitted
 * provided that the following conditions are met: * Redistributions of source code must retain the
 * above copyright notice, this list of conditions and the following disclaimer. * Redistributions
 * in binary form must reproduce the above copyright notice, this list of conditions and the
 * following disclaimer in the documentation and/or other materials provided with the distribution.
 * * Neither the name of the University of Konstanz nor the names of its contributors may be used to
 * endorse or promote products derived from this software without specific prior written permission.
 * <p>
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR
 * IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND
 * FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL <COPYRIGHT HOLDER> BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING,
 * BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS;
 * OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT,
 * STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package org.sirix.access.trx.page;

import com.google.common.base.MoreObjects;
import org.sirix.access.ResourceConfiguration;
import org.sirix.access.trx.node.CommitCredentials;
import org.sirix.access.trx.node.IndexController;
import org.sirix.access.trx.node.InternalResourceManager;
import org.sirix.access.trx.node.xml.XmlResourceManagerImpl;
import org.sirix.api.NodeReadOnlyTrx;
import org.sirix.api.NodeTrx;
import org.sirix.api.PageReadOnlyTrx;
import org.sirix.api.ResourceManager;
import org.sirix.cache.*;
import org.sirix.exception.SirixIOException;
import org.sirix.io.Reader;
import org.sirix.node.DeletedNode;
import org.sirix.node.NodeKind;
import org.sirix.node.interfaces.Record;
import org.sirix.page.*;
import org.sirix.page.interfaces.KeyValuePage;
import org.sirix.page.interfaces.Page;
import org.sirix.settings.Constants;
import org.sirix.settings.Fixed;
import org.sirix.settings.VersioningType;

import javax.annotation.Nonnegative;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

/**
 * <h1>NodePageReadOnlyTrx</h1>
 *
 * <p>
 * Page read-only transaction. The only thing shared amongst transactions is the resource manager.
 * Everything else is exclusive to this transaction. It is required that only a single thread has
 * access to this transaction.
 * </p>
 */
public final class NodePageReadOnlyTrx implements PageReadOnlyTrx {
  /**
   * Page reader exclusively assigned to this transaction.
   */
  private final Reader mPageReader;

  /**
   * Uber page this transaction is bound to.
   */
  private final UberPage mUberPage;

  /**
   * {@link XmlResourceManagerImpl} reference.
   */
  protected final InternalResourceManager<?, ?> mResourceManager;

  /**
   * The revision number, this page trx is bound to.
   */
  private final int mRevisionNumber;

  /**
   * Determines if page reading transaction is closed or not.
   */
  private boolean mClosed;

  /**
   * {@link ResourceConfiguration} instance.
   */
  private final ResourceConfiguration mResourceConfig;

  /**
   * Caches in-memory reconstructed pages of a specific resource.
   */
  private final BufferManager mResourceBufferManager;

  /**
   * Transaction intent log.
   */
  private final TransactionIntentLog mTrxIntentLog;

  /**
   * The transaction-ID.
   */
  private long mTrxId;

  /**
   * Cached name page of this revision.
   */
  private RevisionRootPage mRootPage;

  /**
   * {@link NamePage} reference.
   */
  private NamePage mNamePage;

  private RecordPage mMostRecentlyReadRecordPage;

  /**
   * Standard constructor.
   *
   * @param trxId           the transaction-ID.
   * @param resourceManager the resource manager
   * @param uberPage        {@link UberPage} to start reading from
   * @param revision        key of revision to read from uber page
   * @param reader          reader to read stored pages for this transaction
   * @param trxIntentLog    transaction intent log
   * @param indexController the index controller
   * @param bufferManager   caches in-memory reconstructed pages
   * @throws SirixIOException if reading of the persistent storage fails
   */
  public NodePageReadOnlyTrx(final long trxId,
      final InternalResourceManager<? extends NodeReadOnlyTrx, ? extends NodeTrx> resourceManager,
      final UberPage uberPage, final @Nonnegative int revision, final Reader reader,
      final @Nullable TransactionIntentLog trxIntentLog, final @Nullable IndexController<?, ?> indexController,
      final @Nonnull BufferManager bufferManager, final @Nonnull RevisionRootPageReader revisionRootPageReader) {
    checkArgument(revision >= 0, "Revision must be >= 0.");
    checkArgument(trxId > 0, "Transaction-ID must be >= 0.");
    mTrxId = trxId;
    mResourceBufferManager = bufferManager;
    mTrxIntentLog = trxIntentLog;
    mClosed = false;
    mResourceManager = checkNotNull(resourceManager);
    mResourceConfig = resourceManager.getResourceConfig();
    mPageReader = checkNotNull(reader);
    mUberPage = checkNotNull(uberPage);
    mRevisionNumber = revision;
    mRootPage = revisionRootPageReader.loadRevisionRootPage(this, revision);
    mNamePage = revisionRootPageReader.getNamePage(this, mRootPage);
  }

  private Page loadIndirectPage(final PageReference reference) {
    Page page = reference.getPage();
    if (page == null) {
      if (mTrxIntentLog != null) {
        // Try to get it from the transaction log if it's present.
        final PageContainer cont = mTrxIntentLog.get(reference, this);
        page = cont == null ? null : cont.getComplete();
      }

      if (page == null) {
        if (mTrxIntentLog == null) {
          // Putting to the transaction log afterwards would otherwise render the cached entry invalid
          // as the reference log key is set and the key is reset to Constants.NULL_ID_LONG.
          page = mResourceBufferManager.getPageCache().get(reference);
        }

        if (page == null) {
          page = mPageReader.read(reference, this);

          if (page != null && mTrxIntentLog == null) {
            assert reference.getLogKey() == Constants.NULL_ID_INT
                && reference.getPersistentLogKey() == Constants.NULL_ID_LONG;
            // Put page into buffer manager and set page reference (just to
            // track when the in-memory page must be removed).
            mResourceBufferManager.getPageCache().put(reference, page);
            reference.setPage(page);
          }
        }
      }
    }

    return page;
  }

  @Override
  public long getTrxId() {
    assertNotClosed();
    return mTrxId;
  }

  @Override
  public ResourceManager<? extends NodeReadOnlyTrx, ? extends NodeTrx> getResourceManager() {
    assertNotClosed();
    return mResourceManager;
  }

  /**
   * Make sure that the transaction is not yet closed when calling this method.
   */
  final void assertNotClosed() {
    if (mClosed) {
      throw new IllegalStateException("Transaction is already closed.");
    }
  }

  @Override
  public Optional<Record> getRecord(final long nodeKey, final PageKind pageKind, final @Nonnegative int index) {
    checkNotNull(pageKind);
    assertNotClosed();

    if (nodeKey == Fixed.NULL_NODE_KEY.getStandardProperty()) {
      return Optional.empty();
    }

    final long recordPageKey = pageKey(nodeKey);

    final Optional<Page> page;

    switch (pageKind) {
      case RECORDPAGE:
      case PATHSUMMARYPAGE:
      case PATHPAGE:
      case CASPAGE:
      case NAMEPAGE:
        page = getRecordPage(new IndexLogKey(pageKind, recordPageKey, index, mRevisionNumber));
        break;
      // $CASES-OMITTED$
      default:
        throw new IllegalStateException();
    }

    return page.map(thePage -> ((UnorderedKeyValuePage) thePage).getValue(nodeKey)).flatMap(this::checkItemIfDeleted);
  }

  /**
   * Method to check if an {@link Record} is deleted.
   *
   * @param toCheck node to check
   * @return the {@code node} if it is valid, {@code null} otherwise
   */
  final Optional<Record> checkItemIfDeleted(final @Nullable Record toCheck) {
    if (toCheck instanceof DeletedNode) {
      return Optional.empty();
    } else {
      return Optional.ofNullable(toCheck);
    }
  }

  @Override
  public String getName(final int nameKey, @Nonnull final NodeKind nodeKind) {
    assertNotClosed();
    return mNamePage.getName(nameKey, nodeKind, this);
  }

  @Override
  public byte[] getRawName(final int nameKey, @Nonnull final NodeKind nodeKind) {
    assertNotClosed();
    return mNamePage.getRawName(nameKey, nodeKind, this);
  }

  /**
   * Get revision root page belonging to revision key.
   *
   * @param revisionKey key of revision to find revision root page for
   * @return revision root page of this revision key
   * @throws SirixIOException if something odd happens within the creation process
   */
  @Override
  public RevisionRootPage loadRevRoot(final @Nonnegative int revisionKey) {
    checkArgument(revisionKey >= 0 && revisionKey <= mResourceManager.getMostRecentRevisionNumber(),
                  "%s must be >= 0 and <= last stored revision (%s)!", revisionKey,
                  mResourceManager.getMostRecentRevisionNumber());
    if (mTrxIntentLog == null) {
      final Cache<Integer, RevisionRootPage> cache = mResourceBufferManager.getRevisionRootPageCache();
      RevisionRootPage revisionRootPage = cache.get(revisionKey);
      if (revisionRootPage == null) {
        revisionRootPage = mPageReader.readRevisionRootPage(revisionKey, this);
        cache.put(revisionKey, revisionRootPage);
      }
      return revisionRootPage;
    } else {
      // The indirect page reference either fails horribly or returns a non null
      // instance.
      final PageReference reference = getReferenceToLeafOfSubtree(mUberPage.getIndirectPageReference(), revisionKey, -1,
                                                                  PageKind.UBERPAGE);

      RevisionRootPage page = null;

      if (mTrxIntentLog != null) {
        // Try to get it from the transaction log if it's present.
        final PageContainer cont = mTrxIntentLog.get(reference, this);
        page = cont == null ? null : (RevisionRootPage) cont.getComplete();
      }

      if (page == null) {
        assert reference.getKey() != Constants.NULL_ID_LONG || reference.getLogKey() != Constants.NULL_ID_INT
            || reference.getPersistentLogKey() != Constants.NULL_ID_LONG;
        page = (RevisionRootPage) loadIndirectPage(reference);
      }

      return page;
    }
  }

  @Override
  public NamePage getNamePage(final RevisionRootPage revisionRoot) {
    assertNotClosed();
    return (NamePage) getPage(revisionRoot.getNamePageReference());
  }

  @Override
  public PathSummaryPage getPathSummaryPage(final RevisionRootPage revisionRoot) {
    assertNotClosed();
    return (PathSummaryPage) getPage(revisionRoot.getPathSummaryPageReference());
  }

  @Override
  public PathPage getPathPage(final RevisionRootPage revisionRoot) {
    assertNotClosed();
    return (PathPage) getPage(revisionRoot.getPathPageReference());
  }

  @Override
  public CASPage getCASPage(final RevisionRootPage revisionRoot) {
    assertNotClosed();
    return (CASPage) getPage(revisionRoot.getCASPageReference());
  }

  /**
   * Set the page if it is not set already.
   *
   * @param reference page reference
   * @throws SirixIOException if an I/O error occurs
   */
  private Page getPage(final PageReference reference) {
    Page page = reference.getPage();

    if (page == null) {
      page = loadIndirectPage(reference);
      reference.setPage(page);
    }

    return page;
  }

  @Override
  public final UberPage getUberPage() {
    assertNotClosed();
    return mUberPage;
  }

  @Override
  public <K extends Comparable<? super K>, V extends Record, T extends KeyValuePage<K, V>> Optional<Page> getRecordPage(
      final IndexLogKey indexLogKey) {
    assertNotClosed();
    checkArgument(indexLogKey.getRecordPageKey() >= 0, "recordPageKey must not be negative!");

    if (isMostRecentlyReadPage(indexLogKey)) {
      return Optional.of(mMostRecentlyReadRecordPage.getPage());
      //      final var page = mResourceBufferManager.getUnorderedKeyValuePageCache().get(indexLogKey);
      //
      //      if (page != null) {
      //        return Optional.of(page);
      //      }
    }

    final Optional<PageReference> pageReferenceToRecordPage = getLeafPageReference(
        checkNotNull(indexLogKey.getRecordPageKey()), indexLogKey.getIndex(), checkNotNull(indexLogKey.getIndexType()));

    if (!pageReferenceToRecordPage.isPresent()) {
      return Optional.empty();
    }

    // Try to get from resource buffer manager.
    if (mTrxIntentLog == null) {
      final var page = pageReferenceToRecordPage.get().getPage();

      if (page != null) {
        mMostRecentlyReadRecordPage = new RecordPage(indexLogKey.getIndex(), indexLogKey.getIndexType(),
                                                     indexLogKey.getRecordPageKey(), page);
        return Optional.of(page);
      }

      final Page recordPageFromBuffer = mResourceBufferManager.getRecordPageCache().get(
          pageReferenceToRecordPage.get());

      if (recordPageFromBuffer != null) {
        mMostRecentlyReadRecordPage = new RecordPage(indexLogKey.getIndex(), indexLogKey.getIndexType(),
                                                     indexLogKey.getRecordPageKey(), recordPageFromBuffer);
        return Optional.of(recordPageFromBuffer);
      }
    }

    // Load list of page "fragments" from persistent storage.
    final List<T> pages = getSnapshotPages(pageReferenceToRecordPage.get());

    if (pages.isEmpty()) {
      return Optional.empty();
    }

    final int mileStoneRevision = mResourceConfig.numberOfRevisionsToRestore;
    final VersioningType revisioning = mResourceConfig.revisioningType;
    final Page completePage = revisioning.combineRecordPages(pages, mileStoneRevision, this);

    if (mTrxIntentLog == null) {
      mResourceBufferManager.getRecordPageCache().put(pageReferenceToRecordPage.get(), completePage);
      //      mResourceBufferManager.getUnorderedKeyValuePageCache().put(indexLogKey, completePage);
      pageReferenceToRecordPage.get().setPage(completePage);
    }

    mMostRecentlyReadRecordPage = new RecordPage(indexLogKey.getIndex(), indexLogKey.getIndexType(),
                                                 indexLogKey.getRecordPageKey(), completePage);

    return Optional.of(completePage);
  }

  private boolean isMostRecentlyReadPage(IndexLogKey indexLogKey) {
    return mMostRecentlyReadRecordPage != null
        && mMostRecentlyReadRecordPage.getRecordPageKey() == indexLogKey.getRecordPageKey()
        && mMostRecentlyReadRecordPage.getIndex() == indexLogKey.getIndex()
        && mMostRecentlyReadRecordPage.getPageKind() == indexLogKey.getIndexType();
  }

  final Optional<PageReference> getLeafPageReference(final @Nonnegative long recordPageKey, final int indexNumber,
      final PageKind pageKind) {
    final PageReference pageReferenceToSubtree = getPageReference(mRootPage, pageKind, indexNumber);
    return Optional.ofNullable(
        getReferenceToLeafOfSubtree(pageReferenceToSubtree, recordPageKey, indexNumber, pageKind));
  }

  /**
   * Dereference key/value page reference and get all leaves, the {@link KeyValuePage}s from the
   * revision-trees.
   *
   * @param pageReference optional page reference pointing to the first page
   * @return dereferenced pages
   * @throws SirixIOException if an I/O-error occurs within the creation process
   */
  final <K extends Comparable<? super K>, V extends Record, T extends KeyValuePage<K, V>> List<T> getSnapshotPages(
      final PageReference pageReference) {
    assert pageReference != null;
    final ResourceConfiguration config = mResourceManager.getResourceConfig();
    final int revsToRestore = config.numberOfRevisionsToRestore;
    final int[] revisionsToRead = config.revisioningType.getRevisionRoots(mRootPage.getRevision(), revsToRestore);
    final List<T> pages = new ArrayList<>(revisionsToRead.length);
    boolean first = true;
    for (int i = 0; i < revisionsToRead.length; i++) {
      long refKeyToRecordPage = Constants.NULL_ID_LONG;
      if (first) {
        first = false;
        refKeyToRecordPage = pageReference.getKey();
      } else {
        refKeyToRecordPage = pages.get(pages.size() - 1).getPreviousReferenceKey();
      }

      if (refKeyToRecordPage != Constants.NULL_ID_LONG) {
        final PageReference reference = new PageReference().setKey(refKeyToRecordPage);
        if (reference.getKey() != Constants.NULL_ID_LONG) {
          @SuppressWarnings("unchecked")
          final T page = (T) mPageReader.read(reference, this);
          pages.add(page);
          if (page.size() == Constants.NDP_NODE_COUNT) {
            // Page is full, thus we can skip reconstructing pages with elder versions.
            break;
          }
        }
      } else {
        break;
      }
    }
    return pages;
  }

  /**
   * Get the page reference which points to the right subtree (nodes, path summary nodes, CAS index
   * nodes, Path index nodes or Name index nodes).
   *
   * @param revisionRoot {@link RevisionRootPage} instance
   * @param pageKind     the page kind to determine the right subtree
   * @param index        the index to use
   */
  PageReference getPageReference(final RevisionRootPage revisionRoot, final PageKind pageKind, final int index)
      throws SirixIOException {
    assert revisionRoot != null;
    PageReference ref = null;

    switch (pageKind) {
      case RECORDPAGE:
        ref = revisionRoot.getIndirectPageReference();
        break;
      case CASPAGE:
        ref = getCASPage(revisionRoot).getIndirectPageReference(index);
        break;
      case PATHPAGE:
        ref = getPathPage(revisionRoot).getIndirectPageReference(index);
        break;
      case NAMEPAGE:
        ref = getNamePage(revisionRoot).getIndirectPageReference(index);
        break;
      case PATHSUMMARYPAGE:
        ref = getPathSummaryPage(revisionRoot).getIndirectPageReference(index);
        break;
      // $CASES-OMITTED$
      default:
        throw new IllegalStateException("Only defined for node, path summary, text value and attribute value pages!");
    }

    return ref;
  }

  /**
   * Dereference indirect page reference.
   *
   * @param reference reference to dereference
   * @return dereferenced page
   * @throws SirixIOException     if something odd happens within the creation process
   * @throws NullPointerException if {@code reference} is {@code null}
   */
  @Override
  public IndirectPage dereferenceIndirectPageReference(final PageReference reference) {
    IndirectPage page = null;

    if (mTrxIntentLog != null) {
      // Try to get it from the transaction log if it's present.
      final PageContainer cont = mTrxIntentLog.get(reference, this);
      page = cont == null ? null : (IndirectPage) cont.getComplete();
    }

    if (page == null) {
      // Then try to get the in-memory reference.
      page = (IndirectPage) reference.getPage();
    }

    if (page == null && (reference.getKey() != Constants.NULL_ID_LONG || reference.getLogKey() != Constants.NULL_ID_INT
        || reference.getPersistentLogKey() != Constants.NULL_ID_LONG)) {
      // Then try to get it from the page cache which might read it from the persistent storage on a cache miss.
      page = (IndirectPage) loadIndirectPage(reference);
    }

    return page;
  }

  /**
   * Find reference pointing to leaf page of an indirect tree.
   *
   * @param startReference start reference pointing to the indirect tree
   * @param pageKey        key to look up in the indirect tree
   * @return reference denoted by key pointing to the leaf page
   * @throws SirixIOException if an I/O error occurs
   */
  @Nullable
  @Override
  public PageReference getReferenceToLeafOfSubtree(final PageReference startReference, final @Nonnegative long pageKey,
      final int indexNumber, final @Nonnull PageKind pageKind) {
    assertNotClosed();

    // Initial state pointing to the indirect page of level 0.
    PageReference reference = checkNotNull(startReference);
    checkArgument(pageKey >= 0, "page key must be >= 0!");
    int offset = 0;
    long levelKey = pageKey;
    final int[] inpLevelPageCountExp = mUberPage.getPageCountExp(pageKind);
    final int maxHeight = getCurrentMaxIndirectPageTreeLevel(pageKind, indexNumber, null);

    // Iterate through all levels.
    for (int level = inpLevelPageCountExp.length - maxHeight, height = inpLevelPageCountExp.length;
         level < height; level++) {
      final Page derefPage = dereferenceIndirectPageReference(reference);
      if (derefPage == null) {
        reference = null;
        break;
      } else {
        offset = (int) (levelKey >> inpLevelPageCountExp[level]);
        levelKey -= offset << inpLevelPageCountExp[level];

        try {
          reference = derefPage.getReference(offset);
        } catch (final IndexOutOfBoundsException e) {
          throw new SirixIOException("Node key isn't supported, it's too big!");
        }
      }
    }

    // Return reference to leaf of indirect tree.
    return reference;
  }

  @Override
  public long pageKey(final @Nonnegative long recordKey) {
    assertNotClosed();
    checkArgument(recordKey >= 0, "recordKey must not be negative!");

    return recordKey >> Constants.NDP_NODE_COUNT_EXPONENT;
  }

  @Override
  public int getCurrentMaxIndirectPageTreeLevel(final PageKind pageKind, final int index,
      final RevisionRootPage revisionRootPage) {
    final int maxLevel;
    final RevisionRootPage currentRevisionRootPage = revisionRootPage == null ? mRootPage : revisionRootPage;

    switch (pageKind) {
      case UBERPAGE:
        maxLevel = mUberPage.getCurrentMaxLevelOfIndirectPages();
        break;
      case RECORDPAGE:
        maxLevel = currentRevisionRootPage.getCurrentMaxLevelOfIndirectPages();
        break;
      case CASPAGE:
        maxLevel = getCASPage(currentRevisionRootPage).getCurrentMaxLevelOfIndirectPages(index);
        break;
      case PATHPAGE:
        maxLevel = getPathPage(currentRevisionRootPage).getCurrentMaxLevelOfIndirectPages(index);
        break;
      case NAMEPAGE:
        maxLevel = getNamePage(currentRevisionRootPage).getCurrentMaxLevelOfIndirectPages(index);
        break;
      case PATHSUMMARYPAGE:
        maxLevel = getPathSummaryPage(currentRevisionRootPage).getCurrentMaxLevelOfIndirectPages(index);
        break;
      // $CASES-OMITTED$
      default:
        throw new IllegalStateException("Only defined for node, path summary, text value and attribute value pages!");
    }

    return maxLevel;
  }

  @Override
  public RevisionRootPage getActualRevisionRootPage() {
    assertNotClosed();
    return mRootPage;
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this).add("Session", mResourceManager).add("PageReader", mPageReader).add(
        "UberPage", mUberPage).add("RevRootPage", mRootPage).toString();
  }

  @Override
  public void close() {
    if (!mClosed) {
      mPageReader.close();

      if (!mResourceManager.getNodeReadTrxByTrxId(mTrxId).isPresent())
        mResourceManager.closePageReadTransaction(mTrxId);

      mClosed = true;
    }
  }

  @Override
  public int getNameCount(final int key, @Nonnull final NodeKind kind) {
    assertNotClosed();
    return mNamePage.getCount(key, kind, this);
  }

  @Override
  public boolean isClosed() {
    return mClosed;
  }

  @Override
  public int getRevisionNumber() {
    assertNotClosed();
    return mRootPage.getRevision();
  }

  @Override
  public Reader getReader() {
    assertNotClosed();
    return mPageReader;
  }

  @Override
  public CommitCredentials getCommitCredentials() {
    assertNotClosed();
    return mRootPage.getCommitCredentials();
  }

  @Override
  public int recordPageOffset(final long key) {
    assertNotClosed();
    return (int) (key - ((key >> Constants.NDP_NODE_COUNT_EXPONENT) << Constants.NDP_NODE_COUNT_EXPONENT));
  }

  private static class RecordPage {
    private final int index;

    private final PageKind pageKind;

    private final long recordPageKey;

    private final Page page;

    public RecordPage(int index, PageKind pageKind, long recordPageKey, Page page) {
      this.index = index;
      this.pageKind = pageKind;
      this.recordPageKey = recordPageKey;
      this.page = page;
    }

    public int getIndex() {
      return index;
    }

    public long getRecordPageKey() {
      return recordPageKey;
    }

    public PageKind getPageKind() {
      return pageKind;
    }

    public Page getPage() {
      return page;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o)
        return true;
      if (o == null || getClass() != o.getClass())
        return false;
      RecordPage that = (RecordPage) o;
      return index == that.index && recordPageKey == that.recordPageKey && pageKind == that.pageKind && Objects.equals(
          page, that.page);
    }

    @Override
    public int hashCode() {
      return Objects.hash(index, pageKind, recordPageKey, page);
    }
  }
}
