/*
 * Copyright 2004-2013 H2 Group. Multiple-Licensed under the H2 License, Version
 * 1.0, and under the Eclipse Public License, Version 1.0
 * (http://h2database.com/html/license.html). Initial Developer: H2 Group
 */
package org.h2.index;

import java.util.Iterator;

import org.h2.engine.Constants;
import org.h2.engine.Session;
import org.h2.message.DbException;
import org.h2.mvstore.MVStore;
import org.h2.mvstore.db.MVTableEngine;
import org.h2.mvstore.rtree.MVRTreeMap;
import org.h2.mvstore.rtree.SpatialKey;
import org.h2.result.Row;
import org.h2.result.SearchRow;
import org.h2.result.SortOrder;
import org.h2.table.Column;
import org.h2.table.IndexColumn;
import org.h2.table.RegularTable;
import org.h2.table.TableFilter;
import org.h2.value.Value;
import org.h2.value.ValueGeometry;

import com.vividsolutions.jts.geom.Envelope;
import com.vividsolutions.jts.geom.Geometry;

/**
 * This is an index based on a MVR-TreeMap.
 *
 * @author Thomas Mueller
 * @author Noel Grandin
 * @author Nicolas Fortin, Atelier SIG, IRSTV FR CNRS 24888
 */
public class SpatialTreeIndex extends BaseIndex implements SpatialIndex {

    private static final String MAP_PREFIX  = "RTREE_";

    private final MVRTreeMap<Long> treeMap;
    private final MVStore store;

    private final RegularTable tableData;
    private boolean closed;
    private boolean needRebuild;
    private boolean persistent;

    /**
     * Constructor.
     *
     * @param table the table instance
     * @param id the index id
     * @param indexName the index name
     * @param columns the indexed columns (only one geometry column allowed)
     * @param indexType the index type (only spatial index)
     * @param persistent whether the index data should be persisted
     * @param create whether to create a new index
     * @param session the session.
     */
    public SpatialTreeIndex(RegularTable table, int id, String indexName,
            IndexColumn[] columns, IndexType indexType, boolean persistent,
            boolean create, Session session) {
        if (indexType.isUnique()) {
            throw DbException.getUnsupportedException("not unique");
        }
        if (!persistent && !create) {
            throw DbException.getUnsupportedException("Non persistent index called with create==false");
        }
        if (columns.length > 1) {
            throw DbException.getUnsupportedException("can only do one column");
        }
        if ((columns[0].sortType & SortOrder.DESCENDING) != 0) {
            throw DbException.getUnsupportedException("cannot do descending");
        }
        if ((columns[0].sortType & SortOrder.NULLS_FIRST) != 0) {
            throw DbException.getUnsupportedException("cannot do nulls first");
        }
        if ((columns[0].sortType & SortOrder.NULLS_LAST) != 0) {
            throw DbException.getUnsupportedException("cannot do nulls last");
        }
        initBaseIndex(table, id, indexName, columns, indexType);
        this.needRebuild = create;
        this.persistent = persistent;
        tableData = table;
        if (!database.isStarting()) {
            if (columns[0].column.getType() != Value.GEOMETRY) {
                throw DbException.getUnsupportedException("spatial index on non-geometry column, "
                        + columns[0].column.getCreateSQL());
            }
        }
        if (!persistent) {
            // Index in memory
            store = MVStore.open(null);
            treeMap =  store.openMap("spatialIndex",
                    new MVRTreeMap.Builder<Long>());
        } else {
            if (id < 0) {
                throw DbException.getUnsupportedException("Persistent index with id<0");
            }
            MVTableEngine.init(session.getDatabase());
            store = session.getDatabase().getMvStore().getStore();
            // Called after CREATE SPATIAL INDEX or
            // by PageStore.addMeta
            treeMap =  store.openMap(MAP_PREFIX + getId(),
                    new MVRTreeMap.Builder<Long>());
            if (treeMap.isEmpty()) {
                needRebuild = true;
            }
        }
    }

    @Override
    public void close(Session session) {
        if (persistent) {
            store.store();
        } else {
            store.close();
        }
        closed = true;
    }

    @Override
    public void add(Session session, Row row) {
        if (closed) {
            throw DbException.throwInternalError();
        }
        treeMap.add(getEnvelope(row), row.getKey());
    }

    private SpatialKey getEnvelope(SearchRow row) {
        Value v = row.getValue(columnIds[0]);
        Geometry g = ((ValueGeometry) v.convertTo(Value.GEOMETRY)).getGeometry();
        Envelope env = g.getEnvelopeInternal();
        return new SpatialKey(row.getKey(),
                (float) env.getMinX(), (float) env.getMaxX(),
                (float) env.getMinY(), (float) env.getMaxY());
    }

    @Override
    public void remove(Session session, Row row) {
        if (closed) {
            throw DbException.throwInternalError();
        }
        if (!treeMap.remove(getEnvelope(row), row.getKey())) {
            throw DbException.throwInternalError("row not found");
        }
    }

    @Override
    public Cursor find(TableFilter filter, SearchRow first, SearchRow last) {
        return find(filter.getSession());
    }

    @Override
    public Cursor find(Session session, SearchRow first, SearchRow last) {
        return find(session);
    }

    private Cursor find(Session session) {
        return new SpatialCursor(treeMap.keySet().iterator(), tableData, session);
    }

    @Override
    public Cursor findByGeometry(TableFilter filter, SearchRow intersection) {
        if (intersection == null) {
            return find(filter.getSession());
        }
        return new SpatialCursor(treeMap.findIntersectingKeys(getEnvelope(intersection)), tableData, filter.getSession());
    }

    @Override
    protected long getCostRangeIndex(int[] masks, long rowCount, TableFilter filter, SortOrder sortOrder) {
        rowCount += Constants.COST_ROW_OFFSET;
        long cost = rowCount;
        long rows = rowCount;
        if (masks == null) {
            return cost;
        }
        for (Column column : columns) {
            int index = column.getColumnId();
            int mask = masks[index];
            if ((mask & IndexCondition.SPATIAL_INTERSECTS) != 0) {
                cost = 3 + rows / 4;
            }
        }
        return cost;
    }

    @Override
    public double getCost(Session session, int[] masks, TableFilter filter, SortOrder sortOrder) {
        return getCostRangeIndex(masks, tableData.getRowCountApproximation(), filter, sortOrder);
    }

    @Override
    public void remove(Session session) {
        if (!treeMap.isClosed()) {
            treeMap.removeMap();
        }
    }

    @Override
    public void truncate(Session session) {
        treeMap.clear();
    }

    @Override
    public void checkRename() {
        // nothing to do
    }

    @Override
    public boolean needRebuild() {
        return needRebuild;
    }

    @Override
    public boolean canGetFirstOrLast() {
        return true;
    }

    @Override
    public Cursor findFirstOrLast(Session session, boolean first) {
        if (closed) {
            throw DbException.throwInternalError();
        }
        if (!first) {
            throw DbException.throwInternalError("Spatial Index can only be fetch by ascending order");
        }
        return find(session);
    }

    @Override
    public long getRowCount(Session session) {
        return treeMap.getSize();
    }

    @Override
    public long getRowCountApproximation() {
        return treeMap.getSize();
    }

    @Override
    public long getDiskSpaceUsed() {
        // TODO estimate disk space usage
        return 0;
    }

    /**
     * A cursor to iterate over spatial keys.
     */
    private static final class SpatialCursor implements Cursor {

        private final Iterator<SpatialKey> it;
        private SpatialKey current;
        private final RegularTable tableData;
        private Session session;

        public SpatialCursor(Iterator<SpatialKey> it, RegularTable tableData, Session session) {
            this.it = it;
            this.tableData = tableData;
            this.session = session;
        }

        @Override
        public Row get() {
            return tableData.getRow(session, current.getId());
        }

        @Override
        public SearchRow getSearchRow() {
            return get();
        }

        @Override
        public boolean next() {
            if (!it.hasNext()) {
                return false;
            }
            current = it.next();
            return true;
        }

        @Override
        public boolean previous() {
            return false;
        }

    }

}
