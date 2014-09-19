/*
 * Copyright 2014, Stratio.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.stratio.cassandra.index;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.HashSet;
import java.util.Set;

import org.apache.cassandra.config.ColumnDefinition;
import org.apache.cassandra.db.Column;
import org.apache.cassandra.db.ColumnFamily;
import org.apache.cassandra.db.ColumnFamilyStore;
import org.apache.cassandra.db.DataRange;
import org.apache.cassandra.db.DecoratedKey;
import org.apache.cassandra.db.Row;
import org.apache.cassandra.db.TreeMapBackedSortedColumns;
import org.apache.cassandra.db.filter.QueryFilter;
import org.apache.cassandra.db.marshal.UTF8Type;
import org.apache.cassandra.dht.Token;
import org.apache.cassandra.utils.HeapAllocator;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.Filter;
import org.apache.lucene.search.Sort;

/**
 * {@link RowService} that manages simple rows.
 * 
 * @author Andres de la Pena <adelapena@stratio.com>
 * 
 */
public class RowServiceSimple extends RowService
{

    /** The Lucene's fields to be loaded */
    private static final Set<String> FIELDS_TO_LOAD;
    static
    {
        FIELDS_TO_LOAD = new HashSet<>();
        FIELDS_TO_LOAD.add(PartitionKeyMapper.FIELD_NAME);
    }

    /**
     * Returns a new {@code RowServiceSimple} for manage simple rows.
     * 
     * @param baseCfs
     *            The base column family store.
     * @param columnDefinition
     *            The indexed column definition.
     */
    public RowServiceSimple(ColumnFamilyStore baseCfs, ColumnDefinition columnDefinition) throws IOException
    {
        super(baseCfs, columnDefinition);

        luceneIndex.init(sort());
    }

    /**
     * {@inheritDoc}
     * 
     * These fields are just the partition key.
     */
    @Override
    public Set<String> fieldsToLoad()
    {
        return FIELDS_TO_LOAD;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void indexInner(ByteBuffer key, ColumnFamily columnFamily, long timestamp) throws IOException
    {
        DecoratedKey partitionKey = partitionKeyMapper.decoratedKey(key);

        if (columnFamily.iterator().hasNext())
        // Create or update row
        {
            // Load row
            Row row = row(partitionKey, timestamp);

            // Create document from row
            Document document = new Document();
            tokenMapper.addFields(document, partitionKey);
            partitionKeyMapper.addFields(document, partitionKey);
            schema.addFields(document, metadata, row);

            // Create document's identifying term for insert-update
            Term term = partitionKeyMapper.term(partitionKey);

            // Insert-update on Lucene
            luceneIndex.upsert(term, document);
        }
        else if (columnFamily.deletionInfo() != null)
        // Deleting full row
        {
            Term term = partitionKeyMapper.term(partitionKey);
            luceneIndex.delete(term);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void deleteInner(DecoratedKey partitionKey) throws IOException
    {
        Term term = partitionKeyMapper.term(partitionKey);
        luceneIndex.delete(term);
    }

    /**
     * {@inheritDoc}
     * 
     * The {@link Row} is a physical one.
     */
    @Override
    protected Row row(ScoredDocument scoredDocument, long timestamp)
    {

        // Extract row from document
        Document document = scoredDocument.getDocument();
        DecoratedKey partitionKey = partitionKeyMapper.decoratedKey(document);
        Row row = row(partitionKey, timestamp);

        // Create score column from document score
        Float score = scoredDocument.getScore();
        ByteBuffer columnName = nameType.builder().add(indexedColumnName.key).build();
        ByteBuffer columnValue = UTF8Type.instance.decompose(score.toString());
        Column scoreColumn = new Column(columnName, columnValue, timestamp);

        // Return new row with score column
        ColumnFamily decoratedCf = TreeMapBackedSortedColumns.factory.create(baseCfs.metadata);
        decoratedCf.addColumn(scoreColumn);
        decoratedCf.addAll(row.cf, HeapAllocator.instance);
        return new Row(partitionKey, decoratedCf);
    }

    /**
     * Returns the CQL3 {@link Row} identified by the specified key pair, using the specified time stamp to ignore
     * deleted columns. The {@link Row} is retrieved from the storage engine, so it involves IO operations.
     * 
     * @param partitionKey
     *            The partition key.
     * @param timestamp
     *            The time stamp to ignore deleted columns.
     * @return The CQL3 {@link Row} identified by the specified key pair.
     */
    private Row row(DecoratedKey partitionKey, long timestamp)
    {
        QueryFilter queryFilter = QueryFilter.getIdentityFilter(partitionKey, metadata.cfName, timestamp);
        return row(queryFilter, timestamp);
    }

    /**
     * {@inheritDoc}
     * 
     * The {@link Filter} is based in {@link Token} order.
     */
    @Override
    protected Sort sort()
    {
        return new Sort(tokenMapper.sortFields());
    }

    /**
     * {@inheritDoc}
     * 
     * The {@link Filter} is based on a {@link Token} range.
     */
    @Override
    protected Filter filter(DataRange dataRange)
    {
        return tokenMapper.filter(dataRange);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected Float score(Row row)
    {
        ColumnFamily cf = row.cf;
        ByteBuffer columnName = nameType.builder().add(indexedColumnName.key).build();
        Column column = cf.getColumn(columnName);
        ByteBuffer columnValue = column.value();
        String stringValue = UTF8Type.instance.compose(columnValue);
        return Float.parseFloat(stringValue);

    }

}
