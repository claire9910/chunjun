/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.dtstack.flinkx.metadata.hive2.common;

/**
 * @author : tiezhu
 * @date : 2020/3/9
 * @description :
 */
public class Hive2MetaDataCons {
    public static final String TYPE_TEXT = "TextInputFormat";
    public static final String TYPE_PARQUET = "MapredParquetInputFormat";

    public static final String KEY_TABLE_PROPERTIES = "tableProperties";
    public static final String KEY_PARTITION_COLUMN = "partitionColumn";
    public static final String KEY_INPUT_FORMAT = "inputFormat";
}
