package org.sirix.index.cas;

import static com.google.common.base.Preconditions.checkNotNull;
import org.sirix.api.PageWriteTrx;
import org.sirix.index.IndexDef;
import org.sirix.index.avltree.AVLTreeWriter;
import org.sirix.index.avltree.keyvalue.CASValue;
import org.sirix.index.avltree.keyvalue.NodeReferences;
import org.sirix.index.path.summary.PathSummaryReader;
import org.sirix.node.interfaces.Record;
import org.sirix.page.UnorderedKeyValuePage;

public final class CASIndexListenerFactory {

  public CASIndexListener create(final PageWriteTrx<Long, Record, UnorderedKeyValuePage> pageWriteTrx,
      final PathSummaryReader pathSummaryReader, final IndexDef indexDef) {
    final var pathSummary = checkNotNull(pathSummaryReader);
    final var avlTreeWriter =
        AVLTreeWriter.<CASValue, NodeReferences>getInstance(pageWriteTrx, indexDef.getType(), indexDef.getID());
    final var type = checkNotNull(indexDef.getContentType());
    final var paths = checkNotNull(indexDef.getPaths());

    return new CASIndexListener(pathSummary, avlTreeWriter, paths, type);
  }
}