/**
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
package org.apache.pinot.hadoop.job;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.base.Preconditions;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.pinot.common.config.TableConfig;
import org.apache.pinot.common.data.Schema;
import org.apache.pinot.common.utils.FileUploadDownloadClient;
import org.apache.pinot.common.utils.JsonUtils;
import org.apache.pinot.common.utils.SimpleHttpResponse;
import org.apache.pinot.hadoop.utils.PushLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class DefaultControllerRestApi implements ControllerRestApi {
  private static final Logger LOGGER = LoggerFactory.getLogger(DefaultControllerRestApi.class);

  private final List<PushLocation> _pushLocations;
  private final String _rawTableName;
  private final FileUploadDownloadClient _fileUploadDownloadClient = new FileUploadDownloadClient();

  private static final String OFFLINE = "OFFLINE";

  public DefaultControllerRestApi(List<PushLocation> pushLocations, String rawTableName) {
    LOGGER.info("Push locations are: {} for table: {}", pushLocations, rawTableName);
    _pushLocations = pushLocations;
    _rawTableName = rawTableName;
  }

  @Override
  public TableConfig getTableConfig() {
    for (PushLocation pushLocation : _pushLocations) {
      try {
        SimpleHttpResponse response = _fileUploadDownloadClient.sendGetRequest(FileUploadDownloadClient
            .getRetrieveTableConfigHttpURI(pushLocation.getHost(), pushLocation.getPort(), _rawTableName));
        JsonNode offlineJsonTableConfig = JsonUtils.stringToJsonNode(response.getResponse()).get(OFFLINE);
        if (offlineJsonTableConfig != null) {
          TableConfig offlineTableConfig = TableConfig.fromJSONConfig(offlineJsonTableConfig);
          LOGGER.info("Got table config: {}", offlineTableConfig);
          return offlineTableConfig;
        }
      } catch (Exception e) {
        LOGGER.warn("Caught exception while fetching table config for table: {} from push location: {}", _rawTableName,
            pushLocation, e);
      }
    }
    String errorMessage = String
        .format("Failed to get table config from push locations: %s for table: %s", _pushLocations, _rawTableName);
    LOGGER.error(errorMessage);
    throw new RuntimeException(errorMessage);
  }

  @Override
  public Schema getSchema() {
    for (PushLocation pushLocation : _pushLocations) {
      try {
        SimpleHttpResponse response = _fileUploadDownloadClient.sendGetRequest(FileUploadDownloadClient
            .getRetrieveSchemaHttpURI(pushLocation.getHost(), pushLocation.getPort(), _rawTableName));
        Schema schema = Schema.fromString(response.getResponse());
        LOGGER.info("Got schema: {}", schema);
        return schema;
      } catch (Exception e) {
        LOGGER.warn("Caught exception while fetching schema for table: {} from push location: {}", _rawTableName,
            pushLocation, e);
      }
    }
    String errorMessage =
        String.format("Failed to get schema from push locations: %s for table: %s", _pushLocations, _rawTableName);
    LOGGER.error(errorMessage);
    throw new RuntimeException(errorMessage);
  }

  @Override
  public void pushSegments(FileSystem fileSystem, List<Path> tarFilePaths) {
    LOGGER.info("Start pushing segments: {} to locations: {}", tarFilePaths, _pushLocations);
    for (Path tarFilePath : tarFilePaths) {
      String fileName = tarFilePath.getName();
      Preconditions.checkArgument(fileName.endsWith(JobConfigConstants.TAR_GZ_FILE_EXT));
      String segmentName = fileName.substring(0, fileName.length() - JobConfigConstants.TAR_GZ_FILE_EXT.length());
      for (PushLocation pushLocation : _pushLocations) {
        LOGGER.info("Pushing segment: {} to location: {}", segmentName, pushLocation);
        try (InputStream inputStream = fileSystem.open(tarFilePath)) {
          SimpleHttpResponse response = _fileUploadDownloadClient.uploadSegment(
              FileUploadDownloadClient.getUploadSegmentHttpURI(pushLocation.getHost(), pushLocation.getPort()),
              segmentName, inputStream);
          LOGGER.info("Response {}: {}", response.getStatusCode(), response.getResponse());
        } catch (Exception e) {
          LOGGER.error("Caught exception while pushing segment: {} to location: {}", segmentName, pushLocation, e);
          throw new RuntimeException(e);
        }
      }
    }
  }

  @Override
  public void sendSegmentUris(List<String> segmentUris) {
    LOGGER.info("Start sending segment URIs: {} to locations: {}", segmentUris, _pushLocations);
    for (String segmentUri : segmentUris) {
      for (PushLocation pushLocation : _pushLocations) {
        LOGGER.info("Sending segment URI: {} to location: {}", segmentUri, pushLocation);
        try {
          SimpleHttpResponse response = _fileUploadDownloadClient.sendSegmentUri(
              FileUploadDownloadClient.getUploadSegmentHttpURI(pushLocation.getHost(), pushLocation.getPort()),
              segmentUri);
          LOGGER.info("Response {}: {}", response.getStatusCode(), response.getResponse());
        } catch (Exception e) {
          LOGGER.error("Caught exception while sending segment URI: {} to location: {}", segmentUri, pushLocation, e);
          throw new RuntimeException(e);
        }
      }
    }
  }

  @Override
  public void close()
      throws IOException {
    _fileUploadDownloadClient.close();
  }
}
