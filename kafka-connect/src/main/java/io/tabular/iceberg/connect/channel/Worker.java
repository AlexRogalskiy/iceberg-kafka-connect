/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package io.tabular.iceberg.connect.channel;

import static io.tabular.iceberg.connect.IcebergSinkConfig.DEFAULT_CONTROL_GROUP_PREFIX;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;

import io.tabular.iceberg.connect.IcebergSinkConfig;
import io.tabular.iceberg.connect.data.IcebergWriter;
import io.tabular.iceberg.connect.data.IcebergWriterFactory;
import io.tabular.iceberg.connect.data.Offset;
import io.tabular.iceberg.connect.data.Utilities;
import io.tabular.iceberg.connect.data.WriterResult;
import io.tabular.iceberg.connect.events.CommitReadyPayload;
import io.tabular.iceberg.connect.events.CommitRequestPayload;
import io.tabular.iceberg.connect.events.CommitResponsePayload;
import io.tabular.iceberg.connect.events.Event;
import io.tabular.iceberg.connect.events.EventType;
import io.tabular.iceberg.connect.events.TableName;
import io.tabular.iceberg.connect.events.TopicPartitionOffset;
import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.regex.Pattern;
import org.apache.iceberg.catalog.Catalog;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.relocated.com.google.common.base.Preconditions;
import org.apache.kafka.clients.admin.ListConsumerGroupOffsetsResult;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.connect.errors.ConnectException;
import org.apache.kafka.connect.sink.SinkRecord;
import org.apache.kafka.connect.sink.SinkTaskContext;

public class Worker extends Channel {

  private final Catalog catalog;
  private final IcebergSinkConfig config;
  private final IcebergWriterFactory writerFactory;
  private final SinkTaskContext context;
  private final String controlGroupId;
  private final Map<String, IcebergWriter> writers;
  private final Map<String, Boolean> tableExistsMap;
  private final Map<TopicPartition, Offset> sourceOffsets;

  public Worker(
      Catalog catalog,
      IcebergSinkConfig config,
      KafkaClientFactory clientFactory,
      IcebergWriterFactory writerFactory,
      SinkTaskContext context) {
    // pass transient consumer group ID to which we never commit offsets
    super("worker", DEFAULT_CONTROL_GROUP_PREFIX + UUID.randomUUID(), config, clientFactory);

    this.catalog = catalog;
    this.config = config;
    this.writerFactory = writerFactory;
    this.context = context;
    this.controlGroupId = config.getControlGroupId();
    this.writers = new HashMap<>();
    this.tableExistsMap = new HashMap<>();
    this.sourceOffsets = new HashMap<>();
  }

  public void syncCommitOffsets() {
    Map<TopicPartition, Long> offsets =
        getCommitOffsets().entrySet().stream()
            .collect(toMap(Entry::getKey, entry -> entry.getValue().offset()));
    context.offset(offsets);
  }

  public Map<TopicPartition, OffsetAndMetadata> getCommitOffsets() {
    try {
      ListConsumerGroupOffsetsResult response = admin().listConsumerGroupOffsets(controlGroupId);
      return response.partitionsToOffsetAndMetadata().get().entrySet().stream()
          .filter(entry -> context.assignment().contains(entry.getKey()))
          .collect(toMap(Entry::getKey, Entry::getValue));
    } catch (InterruptedException | ExecutionException e) {
      throw new ConnectException(e);
    }
  }

  public void process() {
    consumeAvailable(Duration.ZERO);
  }

  @Override
  protected boolean receive(Envelope envelope) {
    Event event = envelope.getEvent();
    if (event.getType() != EventType.COMMIT_REQUEST) {
      return false;
    }

    List<WriterResult> writeResults =
        writers.values().stream().map(IcebergWriter::complete).collect(toList());
    Map<TopicPartition, Offset> offsets = new HashMap<>(sourceOffsets);

    tableExistsMap.clear();
    writers.clear();
    sourceOffsets.clear();

    // include all assigned topic partitions even if no messages were read
    // from a partition, as the coordinator will use that to determine
    // when all data for a commit has been received
    List<TopicPartitionOffset> assignments =
        context.assignment().stream()
            .map(
                tp -> {
                  Offset offset = offsets.get(tp);
                  if (offset == null) {
                    offset = Offset.NULL_OFFSET;
                  }
                  return new TopicPartitionOffset(
                      tp.topic(), tp.partition(), offset.getOffset(), offset.getTimestamp());
                })
            .collect(toList());

    UUID commitId = ((CommitRequestPayload) event.getPayload()).getCommitId();

    List<Event> events =
        writeResults.stream()
            .map(
                writeResult ->
                    new Event(
                        config.getControlGroupId(),
                        EventType.COMMIT_RESPONSE,
                        new CommitResponsePayload(
                            writeResult.getPartitionStruct(),
                            commitId,
                            TableName.of(writeResult.getTableIdentifier()),
                            writeResult.getDataFiles(),
                            writeResult.getDeleteFiles())))
            .collect(toList());

    Event readyEvent =
        new Event(
            config.getControlGroupId(),
            EventType.COMMIT_READY,
            new CommitReadyPayload(commitId, assignments));
    events.add(readyEvent);

    send(events, offsets);
    context.requestCommit();

    return true;
  }

  @Override
  public void stop() {
    super.stop();
    writers.values().forEach(IcebergWriter::close);
  }

  public void save(Collection<SinkRecord> sinkRecords) {
    sinkRecords.forEach(this::save);
  }

  private void save(SinkRecord record) {
    // the consumer stores the offsets that corresponds to the next record to consume,
    // so increment the record offset by one
    sourceOffsets.put(
        new TopicPartition(record.topic(), record.kafkaPartition()),
        new Offset(record.kafkaOffset() + 1, record.timestamp()));

    if (config.getDynamicTablesEnabled()) {
      routeRecordDynamically(record);
    } else {
      routeRecordStatically(record);
    }
  }

  private void routeRecordStatically(SinkRecord record) {
    String routeField = config.getTablesRouteField();

    if (routeField == null) {
      // route to all tables
      config
          .getTables()
          .forEach(
              tableName -> {
                getWriterForTable(tableName).write(record);
              });

    } else {
      String routeValue = extractRouteValue(record.value(), routeField);
      if (routeValue != null) {
        config
            .getTables()
            .forEach(
                tableName -> {
                  Pattern tableRouteRegex = config.getTableRouteRegex(tableName);
                  if (tableRouteRegex != null && tableRouteRegex.matcher(routeValue).matches()) {
                    getWriterForTable(tableName).write(record);
                  }
                });
      }
    }
  }

  private void routeRecordDynamically(SinkRecord record) {
    String routeField = config.getTablesRouteField();
    Preconditions.checkNotNull(routeField);

    String routeValue = extractRouteValue(record.value(), routeField);
    if (routeValue != null) {
      String tableName = routeValue.toLowerCase();
      if (tableExists(tableName)) {
        getWriterForTable(tableName).write(record);
      }
    }
  }

  private boolean tableExists(String tableName) {
    return tableExistsMap.computeIfAbsent(
        tableName, notUsed -> catalog.tableExists(TableIdentifier.parse(tableName)));
  }

  private String extractRouteValue(Object recordValue, String routeField) {
    if (recordValue == null) {
      return null;
    }
    Object routeValue = Utilities.extractFromRecordValue(recordValue, routeField);
    return routeValue == null ? null : routeValue.toString();
  }

  private IcebergWriter getWriterForTable(String tableName) {
    return writers.computeIfAbsent(tableName, notUsed -> writerFactory.createWriter(tableName));
  }
}
