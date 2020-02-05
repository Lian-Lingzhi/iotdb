/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.iotdb.db.query.reader.seriesRelated;

import org.apache.iotdb.db.engine.querycontext.QueryDataSource;
import org.apache.iotdb.db.query.context.QueryContext;
import org.apache.iotdb.tsfile.file.metadata.enums.TSDataType;
import org.apache.iotdb.tsfile.read.reader.IPointReader;
import org.apache.iotdb.tsfile.read.TimeValuePair;
import org.apache.iotdb.tsfile.read.common.BatchData;
import org.apache.iotdb.tsfile.read.common.Path;
import org.apache.iotdb.tsfile.read.filter.basic.Filter;

import java.io.IOException;


public class SeriesRawDataPointReader implements IPointReader {

  private final SeriesReader seriesReader;

  private boolean hasCachedTimeValuePair;
  private BatchData batchData;
  private TimeValuePair timeValuePair;


  public SeriesRawDataPointReader(SeriesReader seriesReader) {
    this.seriesReader = seriesReader;
  }

  public SeriesRawDataPointReader(Path seriesPath, TSDataType dataType, QueryContext context,
      QueryDataSource dataSource, Filter timeFilter, Filter valueFilter) {
    this.seriesReader = new SeriesReader(seriesPath, dataType, context, dataSource, timeFilter,
        valueFilter);
  }

  private boolean hasNext() throws IOException {
    while (seriesReader.hasNextChunk()) {
      while (seriesReader.hasNextPage()) {
        if (seriesReader.hasNextOverlappedPage()) {
          return true;
        }
      }
    }
    return false;
  }

  private boolean hasNextSatisfiedInCurrentBatch() {
    while (batchData != null && batchData.hasCurrent()) {
      timeValuePair = new TimeValuePair(batchData.currentTime(),
          batchData.currentTsPrimitiveType());
      hasCachedTimeValuePair = true;
      batchData.next();
      return true;
    }
    return false;
  }

  @Override
  public boolean hasNextTimeValuePair() throws IOException {
    if (hasCachedTimeValuePair) {
      return true;
    }

    if (hasNextSatisfiedInCurrentBatch()) {
      return true;
    }

    // has not cached timeValuePair
    while (hasNext()) {
      batchData = seriesReader.nextOverlappedPage();
      if (hasNextSatisfiedInCurrentBatch()) {
        return true;
      }
    }
    return false;
  }

  @Override
  public TimeValuePair nextTimeValuePair() throws IOException {
    if (hasCachedTimeValuePair || hasNextTimeValuePair()) {
      hasCachedTimeValuePair = false;
      return timeValuePair;
    } else {
      throw new IOException("no next data");
    }
  }

  @Override
  public TimeValuePair currentTimeValuePair() throws IOException {
    return timeValuePair;
  }

  @Override
  public void close() throws IOException {
  }
}
