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
package io.tabular.iceberg.connect.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.Temporal;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.iceberg.Table;
import org.apache.iceberg.TableProperties;
import org.apache.iceberg.data.GenericRecord;
import org.apache.iceberg.data.Record;
import org.apache.iceberg.mapping.MappedField;
import org.apache.iceberg.mapping.NameMapping;
import org.apache.iceberg.mapping.NameMappingParser;
import org.apache.iceberg.relocated.com.google.common.collect.ImmutableList;
import org.apache.iceberg.relocated.com.google.common.collect.ImmutableMap;
import org.apache.iceberg.types.Types;
import org.apache.iceberg.types.Types.TimestampType;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.json.JsonConverter;
import org.apache.kafka.connect.json.JsonConverterConfig;
import org.apache.kafka.connect.storage.ConverterConfig;
import org.apache.kafka.connect.storage.ConverterType;
import org.junit.jupiter.api.Test;

public class RecordConverterTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static final org.apache.iceberg.Schema SCHEMA =
      new org.apache.iceberg.Schema(
          Types.NestedField.required(21, "i", Types.IntegerType.get()),
          Types.NestedField.required(22, "l", Types.LongType.get()),
          Types.NestedField.required(23, "d", Types.DateType.get()),
          Types.NestedField.required(24, "t", Types.TimeType.get()),
          Types.NestedField.required(25, "ts", Types.TimestampType.withoutZone()),
          Types.NestedField.required(26, "tsz", Types.TimestampType.withZone()),
          Types.NestedField.required(27, "fl", Types.FloatType.get()),
          Types.NestedField.required(28, "do", Types.DoubleType.get()),
          Types.NestedField.required(29, "dec", Types.DecimalType.of(9, 2)),
          Types.NestedField.required(30, "s", Types.StringType.get()),
          Types.NestedField.required(31, "u", Types.UUIDType.get()),
          Types.NestedField.required(32, "f", Types.FixedType.ofLength(3)),
          Types.NestedField.required(33, "b", Types.BinaryType.get()),
          Types.NestedField.required(
              34, "li", Types.ListType.ofRequired(35, Types.StringType.get())),
          Types.NestedField.required(
              36,
              "ma",
              Types.MapType.ofRequired(37, 38, Types.StringType.get(), Types.StringType.get())),
          Types.NestedField.optional(39, "extra", Types.StringType.get()));

  // we have 1 unmapped column so exclude that from the count
  private static final int MAPPED_CNT = SCHEMA.columns().size() - 1;

  private static final org.apache.iceberg.Schema NESTED_SCHEMA =
      new org.apache.iceberg.Schema(
          Types.NestedField.required(1, "ii", Types.IntegerType.get()),
          Types.NestedField.required(2, "st", SCHEMA.asStruct()));

  private static final org.apache.iceberg.Schema SIMPLE_SCHEMA =
      new org.apache.iceberg.Schema(
          Types.NestedField.required(1, "ii", Types.IntegerType.get()),
          Types.NestedField.required(2, "st", Types.StringType.get()));

  private static final Schema CONNECT_SCHEMA =
      SchemaBuilder.struct()
          .field("i", Schema.INT32_SCHEMA)
          .field("l", Schema.INT64_SCHEMA)
          .field("d", Schema.STRING_SCHEMA)
          .field("t", Schema.STRING_SCHEMA)
          .field("ts", Schema.STRING_SCHEMA)
          .field("tsz", Schema.STRING_SCHEMA)
          .field("fl", Schema.FLOAT32_SCHEMA)
          .field("do", Schema.FLOAT64_SCHEMA)
          .field("dec", Schema.STRING_SCHEMA)
          .field("s", Schema.STRING_SCHEMA)
          .field("u", Schema.STRING_SCHEMA)
          .field("f", Schema.BYTES_SCHEMA)
          .field("b", Schema.BYTES_SCHEMA)
          .field("li", SchemaBuilder.array(Schema.STRING_SCHEMA))
          .field("ma", SchemaBuilder.map(Schema.STRING_SCHEMA, Schema.STRING_SCHEMA));

  private static final Schema CONNECT_NESTED_SCHEMA =
      SchemaBuilder.struct().field("ii", Schema.INT32_SCHEMA).field("st", CONNECT_SCHEMA);

  private static final LocalDate DATE_VAL = LocalDate.parse("2023-05-18");
  private static final LocalTime TIME_VAL = LocalTime.parse("07:14:21");
  private static final LocalDateTime TS_VAL = LocalDateTime.parse("2023-05-18T07:14:21");
  private static final OffsetDateTime TSZ_VAL = OffsetDateTime.parse("2023-05-18T07:14:21Z");
  private static final BigDecimal DEC_VAL = new BigDecimal("12.34");
  private static final String STR_VAL = "foobar";
  private static final UUID UUID_VAL = UUID.randomUUID();
  private static final ByteBuffer BYTES_VAL = ByteBuffer.wrap(new byte[] {1, 2, 3});
  private static final List<String> LIST_VAL = ImmutableList.of("hello", "world");
  private static final Map<String, String> MAP_VAL = ImmutableMap.of("one", "1", "two", "2");

  private static final JsonConverter JSON_CONVERTER = new JsonConverter();

  static {
    JSON_CONVERTER.configure(
        ImmutableMap.of(
            JsonConverterConfig.SCHEMAS_ENABLE_CONFIG,
            false,
            ConverterConfig.TYPE_CONFIG,
            ConverterType.VALUE.getName()));
  }

  @Test
  public void testMapConvert() {
    Table table = mock(Table.class);
    when(table.schema()).thenReturn(SCHEMA);
    RecordConverter converter = new RecordConverter(table, JSON_CONVERTER);

    Map<String, Object> data = createMapData();
    Record record = converter.convert(data);
    assertRecordValues(record);
  }

  @Test
  public void testNestedMapConvert() {
    Table table = mock(Table.class);
    when(table.schema()).thenReturn(NESTED_SCHEMA);
    RecordConverter converter = new RecordConverter(table, JSON_CONVERTER);

    Map<String, Object> nestedData = createNestedMapData();
    Record record = converter.convert(nestedData);
    assertNestedRecordValues(record);
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testMapToString() throws Exception {
    Table table = mock(Table.class);
    when(table.schema()).thenReturn(SIMPLE_SCHEMA);
    RecordConverter converter = new RecordConverter(table, JSON_CONVERTER);

    Map<String, Object> nestedData = createNestedMapData();
    Record record = converter.convert(nestedData);

    String str = (String) record.getField("st");
    Map<String, Object> map = (Map<String, Object>) MAPPER.readValue(str, Map.class);
    assertEquals(MAPPED_CNT, map.size());
  }

  @Test
  public void testStructConvert() {
    Table table = mock(Table.class);
    when(table.schema()).thenReturn(SCHEMA);
    RecordConverter converter = new RecordConverter(table, JSON_CONVERTER);

    Struct data = createStructData();
    Record record = converter.convert(data);
    assertRecordValues(record);
  }

  @Test
  public void testNestedStructConvert() {
    Table table = mock(Table.class);
    when(table.schema()).thenReturn(NESTED_SCHEMA);
    RecordConverter converter = new RecordConverter(table, JSON_CONVERTER);

    Struct nestedData = createNestedStructData();
    Record record = converter.convert(nestedData);
    assertNestedRecordValues(record);
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testStructToString() throws Exception {
    Table table = mock(Table.class);
    when(table.schema()).thenReturn(SIMPLE_SCHEMA);
    RecordConverter converter = new RecordConverter(table, JSON_CONVERTER);

    Struct nestedData = createNestedStructData();
    Record record = converter.convert(nestedData);

    String str = (String) record.getField("st");
    Map<String, Object> map = (Map<String, Object>) MAPPER.readValue(str, Map.class);
    assertEquals(MAPPED_CNT, map.size());
  }

  @Test
  public void testNameMapping() {
    Table table = mock(Table.class);
    when(table.schema()).thenReturn(SIMPLE_SCHEMA);

    NameMapping nameMapping = NameMapping.of(MappedField.of(1, ImmutableList.of("renamed_ii")));
    when(table.properties())
        .thenReturn(
            ImmutableMap.of(
                TableProperties.DEFAULT_NAME_MAPPING, NameMappingParser.toJson(nameMapping)));

    RecordConverter converter = new RecordConverter(table, JSON_CONVERTER);

    Map<String, Object> data = ImmutableMap.of("renamed_ii", 123);
    Record record = converter.convert(data);
    assertEquals(123, record.getField("ii"));
  }

  @Test
  public void testTimestampWithZoneConversion() {
    OffsetDateTime expected = OffsetDateTime.parse("2023-05-18T11:22:33Z");
    long expectedMillis = expected.toInstant().toEpochMilli();
    convertToTimestamps(expected, expectedMillis, TimestampType.withZone());
  }

  @Test
  public void testTimestampWithoutZoneConversion() {
    LocalDateTime expected = LocalDateTime.parse("2023-05-18T11:22:33");
    long expectedMillis = expected.atZone(ZoneOffset.UTC).toInstant().toEpochMilli();
    convertToTimestamps(expected, expectedMillis, TimestampType.withoutZone());
  }

  private void convertToTimestamps(Temporal expected, long expectedMillis, TimestampType type) {
    Table table = mock(Table.class);
    when(table.schema()).thenReturn(SIMPLE_SCHEMA);
    RecordConverter converter = new RecordConverter(table, JSON_CONVERTER);

    List<Object> inputList =
        ImmutableList.of(
            "2023-05-18T11:22:33Z",
            "2023-05-18 11:22:33Z",
            "2023-05-18T11:22:33+00",
            "2023-05-18 11:22:33+00",
            "2023-05-18T11:22:33+00:00",
            "2023-05-18 11:22:33+00:00",
            "2023-05-18T11:22:33+0000",
            "2023-05-18 11:22:33+0000",
            "2023-05-18T11:22:33",
            "2023-05-18 11:22:33",
            expectedMillis,
            new Date(expectedMillis),
            OffsetDateTime.ofInstant(Instant.ofEpochMilli(expectedMillis), ZoneOffset.UTC),
            LocalDateTime.ofInstant(Instant.ofEpochMilli(expectedMillis), ZoneOffset.UTC));

    inputList.forEach(
        input -> {
          Temporal ts = converter.convertTimestampValue(input, type);
          assertEquals(expected, ts);
        });
  }

  private Map<String, Object> createMapData() {
    return ImmutableMap.<String, Object>builder()
        .put("i", 1)
        .put("l", 2L)
        .put("d", DATE_VAL.toString())
        .put("t", TIME_VAL.toString())
        .put("ts", TS_VAL.toString())
        .put("tsz", TSZ_VAL.toString())
        .put("fl", 1.1f)
        .put("do", 2.2d)
        .put("dec", DEC_VAL.toString())
        .put("s", STR_VAL)
        .put("u", UUID_VAL.toString())
        .put("f", Base64.getEncoder().encodeToString(BYTES_VAL.array()))
        .put("b", Base64.getEncoder().encodeToString(BYTES_VAL.array()))
        .put("li", LIST_VAL)
        .put("ma", MAP_VAL)
        .build();
  }

  private Map<String, Object> createNestedMapData() {
    return ImmutableMap.<String, Object>builder().put("ii", 11).put("st", createMapData()).build();
  }

  private Struct createStructData() {
    return new Struct(CONNECT_SCHEMA)
        .put("i", 1)
        .put("l", 2L)
        .put("d", DATE_VAL.toString())
        .put("t", TIME_VAL.toString())
        .put("ts", TS_VAL.toString())
        .put("tsz", TSZ_VAL.toString())
        .put("fl", 1.1f)
        .put("do", 2.2d)
        .put("dec", DEC_VAL.toString())
        .put("s", STR_VAL)
        .put("u", UUID_VAL.toString())
        .put("f", BYTES_VAL.array())
        .put("b", BYTES_VAL.array())
        .put("li", LIST_VAL)
        .put("ma", MAP_VAL);
  }

  private Struct createNestedStructData() {
    return new Struct(CONNECT_NESTED_SCHEMA).put("ii", 11).put("st", createStructData());
  }

  private void assertRecordValues(Record record) {
    GenericRecord rec = (GenericRecord) record;
    assertEquals(1, rec.getField("i"));
    assertEquals(2L, rec.getField("l"));
    assertEquals(DATE_VAL, rec.getField("d"));
    assertEquals(TIME_VAL, rec.getField("t"));
    assertEquals(TS_VAL, rec.getField("ts"));
    assertEquals(TSZ_VAL, rec.getField("tsz"));
    assertEquals(1.1f, rec.getField("fl"));
    assertEquals(2.2d, rec.getField("do"));
    assertEquals(DEC_VAL, rec.getField("dec"));
    assertEquals(STR_VAL, rec.getField("s"));
    assertEquals(UUID_VAL, rec.getField("u"));
    assertEquals(BYTES_VAL, rec.getField("f"));
    assertEquals(BYTES_VAL, rec.getField("b"));
    assertEquals(LIST_VAL, rec.getField("li"));
    assertEquals(MAP_VAL, rec.getField("ma"));
  }

  private void assertNestedRecordValues(Record record) {
    GenericRecord rec = (GenericRecord) record;
    assertEquals(11, rec.getField("ii"));
    assertRecordValues((GenericRecord) rec.getField("st"));
  }
}
