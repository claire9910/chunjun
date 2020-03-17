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
package com.dtstack.flinkx.metadata.hive2.reader;

import com.dtstack.flinkx.config.DataTransferConfig;
import com.dtstack.flinkx.metadata.hive2.inputformat.Hive2MetadataInputFormat;
import com.dtstack.flinkx.metadata.reader.inputformat.MetaDataInputFormatBuilder;
import com.dtstack.flinkx.metadata.reader.reader.MetaDataReader;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;

/**
 * @author : tiezhu
 * @date : 2020/3/9
 */
public class Hive2MetadataReader extends MetaDataReader {
    public Hive2MetadataReader(DataTransferConfig config, StreamExecutionEnvironment env) {
        super(config, env);
        driverName = "org.apache.hive.jdbc.HiveDriver";
    }

    @Override
    protected MetaDataInputFormatBuilder getBuilder(){
        return new MetaDataInputFormatBuilder(new Hive2MetadataInputFormat());
    }
}
