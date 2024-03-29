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

package org.apache.pinot.thirdeye.detection;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.wordnik.swagger.annotations.Api;
import com.wordnik.swagger.annotations.ApiOperation;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.xml.bind.ValidationException;
import org.apache.commons.lang3.StringUtils;
import org.apache.pinot.thirdeye.anomaly.detection.AnomalyDetectionInputContextBuilder;
import org.apache.pinot.thirdeye.api.Constants;
import org.apache.pinot.thirdeye.datalayer.bao.AlertConfigManager;
import org.apache.pinot.thirdeye.datalayer.bao.AnomalyFunctionManager;
import org.apache.pinot.thirdeye.datalayer.bao.DatasetConfigManager;
import org.apache.pinot.thirdeye.datalayer.bao.DetectionAlertConfigManager;
import org.apache.pinot.thirdeye.datalayer.bao.DetectionConfigManager;
import org.apache.pinot.thirdeye.datalayer.bao.MergedAnomalyResultManager;
import org.apache.pinot.thirdeye.datalayer.bao.MetricConfigManager;
import org.apache.pinot.thirdeye.datalayer.dto.AlertConfigDTO;
import org.apache.pinot.thirdeye.datalayer.dto.AnomalyFunctionDTO;
import org.apache.pinot.thirdeye.datalayer.dto.DatasetConfigDTO;
import org.apache.pinot.thirdeye.datalayer.dto.DetectionAlertConfigDTO;
import org.apache.pinot.thirdeye.datalayer.dto.DetectionConfigDTO;
import org.apache.pinot.thirdeye.datalayer.dto.MergedAnomalyResultDTO;
import org.apache.pinot.thirdeye.datalayer.dto.MetricConfigDTO;
import org.apache.pinot.thirdeye.datalayer.util.Predicate;
import org.apache.pinot.thirdeye.detection.yaml.YamlDetectionAlertConfigTranslator;
import org.apache.pinot.thirdeye.detection.yaml.YamlResource;
import org.joda.time.Period;
import org.omg.CORBA.OBJ_ADAPTER;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import static org.apache.pinot.thirdeye.anomaly.merge.AnomalyMergeStrategy.*;
import static org.apache.pinot.thirdeye.detection.yaml.YamlDetectionAlertConfigTranslator.*;


/**
 * The Detection migration resource.
 */
@Path("/migrate")
@Api(tags = {Constants.YAML_TAG})
public class DetectionMigrationResource {
  private static final Logger LOGGER = LoggerFactory.getLogger(DetectionMigrationResource.class);
  private static final String PROP_WINDOW_DELAY = "windowDelay";
  private static final String PROP_WINDOW_DELAY_UNIT = "windowDelayUnit";
  private static final String PROP_WINDOW_SIZE = "windowSize";
  private static final String PROP_WINDOW_UNIT = "windowUnit";

  static final String MIGRATED_TAG = "_thirdeye_migrated";

  private final AnomalyFunctionManager anomalyFunctionDAO;
  private final DetectionConfigManager detectionConfigDAO;
  private final DetectionAlertConfigManager detectionAlertConfigDAO;
  private final DatasetConfigManager datasetConfigDAO;
  private final MergedAnomalyResultManager mergedAnomalyResultDAO;
  private final AlertConfigManager alertConfigDAO;
  private final MetricConfigManager metricConfigDAO;
  private final Yaml yaml;

  /**
   * Instantiates a new Detection migration resource.
   */
  public DetectionMigrationResource(
      AnomalyFunctionManager anomalyFunctionDAO,
      AlertConfigManager alertConfigDAO,
      MetricConfigManager metricConfigDAO,
      DetectionConfigManager detectionConfigDAO,
      DetectionAlertConfigManager detectionAlertConfigDAO,
      DatasetConfigManager datasetConfigDAO,
      MergedAnomalyResultManager mergedAnomalyResultDAO) {
    this.anomalyFunctionDAO = anomalyFunctionDAO;
    this.detectionConfigDAO = detectionConfigDAO;
    this.detectionAlertConfigDAO = detectionAlertConfigDAO;
    this.alertConfigDAO = alertConfigDAO;
    this.metricConfigDAO = metricConfigDAO;
    this.datasetConfigDAO = datasetConfigDAO;
    this.mergedAnomalyResultDAO = mergedAnomalyResultDAO;
    DumperOptions options = new DumperOptions();
    options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
    options.setPrettyFlow(true);
    this.yaml = new Yaml(options);
  }

  private Map<String, Object> translateAnomalyFunctionToYaml(AnomalyFunctionDTO anomalyFunctionDTO) {
    Map<String, Object> yamlConfigs = new LinkedHashMap<>();
    yamlConfigs.put("detectionName", anomalyFunctionDTO.getFunctionName());
    yamlConfigs.put("description", "<Please edit and provide a description for this alert>");
    yamlConfigs.put("metric", anomalyFunctionDTO.getMetric());
    yamlConfigs.put("active", anomalyFunctionDTO.getIsActive());
    yamlConfigs.put("dataset", anomalyFunctionDTO.getCollection());
    yamlConfigs.put("pipelineType", "Composite");
    if (StringUtils.isNotBlank(anomalyFunctionDTO.getExploreDimensions())) {
      // dimension explore and data filter
      yamlConfigs.put("dimensionExploration",
          getDimensionExplorationParams(anomalyFunctionDTO));
    }
    if (anomalyFunctionDTO.getFilters() != null){
      yamlConfigs.put("filters",
          AnomalyDetectionInputContextBuilder.getFiltersForFunction(anomalyFunctionDTO.getFilters()).asMap());
    }

    Map<String, Object> ruleYaml = new LinkedHashMap<>();

    // detection
    if (anomalyFunctionDTO.getType().equals("WEEK_OVER_WEEK_RULE")){
      // wo1w change detector
      ruleYaml.put("detection", Collections.singletonList(ImmutableMap.of("name", "detection_rule1", "type", "PERCENTAGE_RULE",
          "params", getPercentageChangeRuleDetectorParams(anomalyFunctionDTO))));
    } else if (anomalyFunctionDTO.getType().equals("MIN_MAX_THRESHOLD")){
      // threshold detector
      ruleYaml.put("detection", Collections.singletonList(ImmutableMap.of("name", "detection_rule1", "type", "THRESHOLD",
          "params", getMinMaxThresholdRuleDetectorParams(anomalyFunctionDTO))));
    } else{
      // algorithm detector
      Map<String, Object> detectionProperties = new HashMap<>();
      if (anomalyFunctionDTO.getWindowDelay() != 0) {
        detectionProperties.put(PROP_WINDOW_DELAY, anomalyFunctionDTO.getWindowDelay());
        detectionProperties.put(PROP_WINDOW_DELAY_UNIT, anomalyFunctionDTO.getWindowDelayUnit().toString());
      }
      detectionProperties.put("name", "detection_rule1");
      detectionProperties.put("type", "MIGRATED_ALGORITHM");
      detectionProperties.put("params", getAlgorithmDetectorParams(anomalyFunctionDTO));
      detectionProperties.put(PROP_WINDOW_SIZE, anomalyFunctionDTO.getWindowSize());
      detectionProperties.put(PROP_WINDOW_UNIT, anomalyFunctionDTO.getWindowUnit().toString());
      detectionProperties.put("bucketPeriod", getBucketPeriod(anomalyFunctionDTO));

      ruleYaml.put("detection", Collections.singletonList(detectionProperties));
    }

    // filters
    Map<String, String> alertFilter = anomalyFunctionDTO.getAlertFilter();

    if (alertFilter != null && !alertFilter.isEmpty()){
      Map<String, Object> filterYaml = new LinkedHashMap<>();
      if (!alertFilter.containsKey("thresholdField")) {
        // algorithm alert filter
        filterYaml = ImmutableMap.of("name", "filter_rule1", "type", "MIGRATED_ALGORITHM_FILTER", "params", getAlertFilterParams(anomalyFunctionDTO));
      } else {
        // threshold filter migrate to rule filters
        // site wide impact filter migrate to rule based swi filter
        if (anomalyFunctionDTO.getAlertFilter().get("thresholdField").equals("impactToGlobal")){
          filterYaml.put("type", "SITEWIDE_IMPACT_FILTER");
          filterYaml.put("name", "filter_rule1");
          filterYaml.put("params", getSiteWideImpactFilterParams(anomalyFunctionDTO));
        }
        // weight filter migrate to rule based percentage change filter
        if (anomalyFunctionDTO.getAlertFilter().get("thresholdField").equals("weight")){
          filterYaml.put("name", "filter_rule1");
          filterYaml.put("type", "PERCENTAGE_CHANGE_FILTER");
          filterYaml.put("params", getPercentageChangeFilterParams(anomalyFunctionDTO));
        }
      }
      ruleYaml.put("filter", Collections.singletonList(filterYaml));
    }

    yamlConfigs.put("rules", Collections.singletonList(ruleYaml));

    // merger configs
    if (anomalyFunctionDTO.getAnomalyMergeConfig() != null ) {
      Map<String, Object> mergerYaml = new LinkedHashMap<>();
      if (anomalyFunctionDTO.getAnomalyMergeConfig().getMergeStrategy() == FUNCTION_DIMENSIONS){
        mergerYaml.put("maxGap", anomalyFunctionDTO.getAnomalyMergeConfig().getSequentialAllowedGap());
        mergerYaml.put("maxDuration", anomalyFunctionDTO.getAnomalyMergeConfig().getMaxMergeDurationLength());
      }
      yamlConfigs.put("merger", mergerYaml);
    }

    return yamlConfigs;
  }

  private Map<String, Object> getDimensionExplorationParams(AnomalyFunctionDTO functionDTO) {
    Map<String, Object> dimensionExploreYaml = new LinkedHashMap<>();
    dimensionExploreYaml.put("dimensions", Collections.singletonList(functionDTO.getExploreDimensions()));
    if (functionDTO.getDataFilter() != null && !functionDTO.getDataFilter().isEmpty() && functionDTO.getDataFilter().get("type").equals("average_threshold")) {
      // migrate average threshold data filter
      dimensionExploreYaml.put("dimensionFilterMetric", functionDTO.getDataFilter().get("metricName"));
      dimensionExploreYaml.put("minValue", Double.valueOf(functionDTO.getDataFilter().get("threshold")));
      dimensionExploreYaml.put("minLiveZone", functionDTO.getDataFilter().get("minLiveZone"));
    }
    if (functionDTO.getType().equals("MIN_MAX_THRESHOLD")){
      // migrate volume threshold
      Properties properties = AnomalyFunctionDTO.toProperties(functionDTO.getProperties());
      if (properties.containsKey("averageVolumeThreshold")){
        dimensionExploreYaml.put("minValue", properties.getProperty("averageVolumeThreshold"));
      }
    }
    return dimensionExploreYaml;
  }

  private Map<String, Object> getPercentageChangeFilterParams(AnomalyFunctionDTO functionDTO) {
    Map<String, Object> filterYamlParams = new LinkedHashMap<>();
    filterYamlParams.put("threshold", Math.abs(Double.valueOf(functionDTO.getAlertFilter().get("maxThreshold"))));
    filterYamlParams.put("pattern", "up_or_down");
    return filterYamlParams;
  }

  private Map<String, Object> getSiteWideImpactFilterParams(AnomalyFunctionDTO functionDTO) {
    Map<String, Object> filterYamlParams = new LinkedHashMap<>();
    filterYamlParams.put("threshold", Math.abs(Double.valueOf(functionDTO.getAlertFilter().get("maxThreshold"))));
    filterYamlParams.put("pattern", "up_or_down");
    filterYamlParams.put("sitewideMetricName", functionDTO.getGlobalMetric());
    filterYamlParams.put("sitewideCollection", functionDTO.getCollection());
    if (StringUtils.isNotBlank(functionDTO.getGlobalMetricFilters())) {
      filterYamlParams.put("filters",
          AnomalyDetectionInputContextBuilder.getFiltersForFunction(functionDTO.getGlobalMetricFilters()).asMap());
    }
    return filterYamlParams;
  }

  private Map<String, Object> getAlertFilterParams(AnomalyFunctionDTO functionDTO) {
    Map<String, Object> filterYamlParams = new LinkedHashMap<>();
    Map<String, Object> params = new HashMap<>();
    filterYamlParams.put("configuration", params);
    params.putAll(functionDTO.getAlertFilter());
    params.put("bucketPeriod", getBucketPeriod(functionDTO));
    params.put("timeZone", getTimezone(functionDTO));
    return filterYamlParams;
  }

  private String getTimezone(AnomalyFunctionDTO functionDTO) {
    DatasetConfigDTO datasetConfigDTO = this.datasetConfigDAO.findByDataset(functionDTO.getCollection());
    return datasetConfigDTO.getTimezone();
  }

  private String getBucketPeriod(AnomalyFunctionDTO functionDTO) {
    return new Period(TimeUnit.MILLISECONDS.convert(functionDTO.getBucketSize(), functionDTO.getBucketUnit())).toString();
  }

  private Map<String, Object> getPercentageChangeRuleDetectorParams(AnomalyFunctionDTO functionDTO) {
    Map<String, Object> detectorYaml = new LinkedHashMap<>();
    Properties properties = AnomalyFunctionDTO.toProperties(functionDTO.getProperties());
    double threshold = Double.valueOf(properties.getProperty("changeThreshold"));
    if (properties.containsKey("changeThreshold")){
      detectorYaml.put("percentageChange", Math.abs(threshold));
      if (threshold > 0){
        detectorYaml.put("pattern", "UP");
      } else {
        detectorYaml.put("pattern", "DOWN");
      }
    }
    return detectorYaml;
  }

  private Map<String, Object> getMinMaxThresholdRuleDetectorParams(AnomalyFunctionDTO functionDTO) {
    Map<String, Object> detectorYaml = new LinkedHashMap<>();
    Properties properties = AnomalyFunctionDTO.toProperties(functionDTO.getProperties());
    if (properties.containsKey("min")){
      detectorYaml.put("min", properties.getProperty("min"));
    }
    if (properties.containsKey("max")){
      detectorYaml.put("max", properties.getProperty("max"));
    }
    return detectorYaml;
  }

  private Map<String, Object> getAlgorithmDetectorParams(AnomalyFunctionDTO functionDTO) {
    Map<String, Object> detectorYaml = new LinkedHashMap<>();
    Map<String, Object> params = new LinkedHashMap<>();
    detectorYaml.put("configuration", params);
    Properties properties = AnomalyFunctionDTO.toProperties(functionDTO.getProperties());
    for (Map.Entry<Object, Object> property : properties.entrySet()) {
      params.put((String) property.getKey(), property.getValue());
    }
    params.put("variables.bucketPeriod", getBucketPeriod(functionDTO));
    params.put("variables.timeZone", getTimezone(functionDTO));
    return detectorYaml;
  }

  long migrateLegacyAnomalyFunction(long anomalyFunctionId) throws ValidationException {
    AnomalyFunctionDTO anomalyFunctionDTO = this.anomalyFunctionDAO.findById(anomalyFunctionId);

    // Verify if function is still valid
    validateFunction(anomalyFunctionDTO);

    return migrateLegacyAnomalyFunction(anomalyFunctionDTO);
  }

  private long migrateLegacyAnomalyFunction(AnomalyFunctionDTO anomalyFunctionDTO) {
    DetectionConfigDTO detectionConfig;

    LOGGER.info(String.format("[MIG] Migrating anomaly function %d %s", anomalyFunctionDTO.getId(),
        anomalyFunctionDTO.getFunctionName()));

    // Check if this anomaly function is already migrated
    if (anomalyFunctionDTO.getFunctionName().contains(MIGRATED_TAG)) {
      LOGGER.info(String.format("[MIG] Anomaly function %d is already migrated.", anomalyFunctionDTO.getId()));

      // Fetch the migrated config id and return
      String funcName = anomalyFunctionDTO.getFunctionName();
      return Long.parseLong(funcName.substring(funcName.lastIndexOf("_") + 1, funcName.length()));
    }

    // Migrate anomaly function config to the detection config by converting to YAML and then to Detection Config
    try {
      Map<String, Object> detectionYAMLMap = translateAnomalyFunctionToYaml(anomalyFunctionDTO);
      detectionConfig = new YamlResource().translateToDetectionConfig(detectionYAMLMap);
      Preconditions.checkNotNull(detectionConfig);
    } catch (Exception e) {
      throw new RuntimeException("Error translating anomaly function config to the detection config" + e.getMessage());
    }

    // Save the migrated anomaly function
    detectionConfigDAO.save(detectionConfig);
    if (detectionConfig.getId() == null) {
      throw new RuntimeException("Error saving the new detection config.");
    }

    // Hack to retain Anomaly function owner
    detectionConfig.setCreatedBy(anomalyFunctionDTO.getCreatedBy());
    detectionConfigDAO.update(detectionConfig);
    if (detectionConfig.getId() == null) {
      throw new RuntimeException("Error saving the new detection config after updating the owner.");
    }

    // Point all the associated anomalies to the migrated anomaly function.
    List<MergedAnomalyResultDTO> mergedAnomalyResultDTOS = mergedAnomalyResultDAO.findByPredicate(Predicate.EQ("functionId", anomalyFunctionDTO.getId()));
    for (MergedAnomalyResultDTO anomaly : mergedAnomalyResultDTOS) {
      // Drop the baseline and current values from the anomalies.
      if (anomaly.getProperties() != null) {
        anomaly.getProperties().remove("anomalyTimelinesView");
      }
      anomaly.setDetectionConfigId(detectionConfig.getId());
      int affectedRows = mergedAnomalyResultDAO.update(anomaly);
      if (affectedRows == 0) {
        throw new RuntimeException("Failed to update the anomaly " + anomaly.getId() + " with the new detection id"
            + " for anomaly function " + detectionConfig.getId());
      }
    }

    // Mark the old anomaly function as migrated
    anomalyFunctionDTO.setActive(false);
    anomalyFunctionDTO.setFunctionName(anomalyFunctionDTO.getFunctionName() + MIGRATED_TAG + "_" + detectionConfig.getId());
    int affectedRows = this.anomalyFunctionDAO.update(anomalyFunctionDTO);
    if (affectedRows == 0) {
      throw new RuntimeException("Anomaly function migrated successfully but failed to disable and update the"
          + " migration status of the old anomaly function. Recommend doing it manually. Migrated detection id "
          + detectionConfig.getId());
    }

    LOGGER.info(String.format("[MIG] Successfully migrated anomaly function %d %s", anomalyFunctionDTO.getId(),
        anomalyFunctionDTO.getFunctionName()));
    return detectionConfig.getId();
  }

  private void migrateLegacyNotification(AlertConfigDTO alertConfigDTO) {
    int anomalyFailureCount = 0;
    int anomalyWarningCount = 0;
    String alertName = alertConfigDTO.getName();

    LOGGER.info(String.format("[MIG] Migrating alert %d %s", alertConfigDTO.getId(), alertName));

    // Skip if the alert is already migrated
    if (alertConfigDTO.getName().contains(MIGRATED_TAG)) {
      LOGGER.info(String.format("[MIG] Alert %d is already migrated. Skipping!", alertConfigDTO.getId()));
      return;
    }

    // Migrate all the subscribed anomaly functions. Note that this will update the state of old anomaly functions.
    List<Long> detectionIds = ConfigUtils.getLongs(alertConfigDTO.getEmailConfig().getFunctionIds());
    List<Long> filteredIds = new ArrayList<>();
    for (long detectionId : detectionIds) {
      try {
        migrateLegacyAnomalyFunction(detectionId);
        filteredIds.add(detectionId);
      } catch (ValidationException e) {
        anomalyWarningCount++;
        // Ignore those anomaly functions which are pointing to invalid entities
        LOGGER.warn("[MIG] Validation error while migrating anomaly function {}. Error ", detectionId, e.getMessage());
      } catch (Exception e) {
        anomalyFailureCount++;
        LOGGER.error("[MIG] Error while migrating anomaly function {}. Error ", detectionId, e);
      }
    }
    alertConfigDTO.getEmailConfig().setFunctionIds(filteredIds);

    // Translate the old alert and capture the state.
    Map<String, Object> detectionAlertYaml = translateAlertToYaml(alertConfigDTO);

    // Migrate the alert/notification group
    DetectionAlertConfigDTO alertConfig = new YamlDetectionAlertConfigTranslator(detectionConfigDAO).translate(detectionAlertYaml);
    List<DetectionAlertConfigDTO> alertDTOs = detectionAlertConfigDAO.findByPredicate(Predicate.EQ("name", alertConfig.getName()));
    if (!alertDTOs.isEmpty()) {
      LOGGER.warn("[MIG] Looks like this alert was already migrated. old id = " + alertConfig.getId() + " new id = "
          + alertDTOs.get(0).getId());
    } else {
      detectionAlertConfigDAO.save(alertConfig);
      if (alertConfig.getId() == null) {
        throw new RuntimeException("Error while saving the migrated alert config for " + alertName);
      }

      // Hack to retain subscription group owner
      alertConfig.setCreatedBy(alertConfigDTO.getCreatedBy());
      detectionAlertConfigDAO.update(alertConfig);
      if (alertConfig.getId() == null) {
        throw new RuntimeException("Error saving the migrated alert config after updating the owner.");
      }
    }

    // Update migration status and disable the old alert
    alertConfigDTO.setName(alertName + MIGRATED_TAG + "_" + alertConfig.getId());
    alertConfigDTO.setActive(false);
    int affectedRows = alertConfigDAO.update(alertConfigDTO);
    if (affectedRows == 0) {
      throw new RuntimeException(
          "Alert migrated successfully but failed to disable and update the migration status" + " of the old alert."
              + " Migrated alert id " + alertConfig.getId());
    }

    if (anomalyFailureCount == 0 && anomalyWarningCount == 0) {
      LOGGER.info(String.format("[MIG] Successfully migrated alert %d %s", alertConfigDTO.getId(), alertName));
    } else {
      throw new RuntimeException("Failures/Warnings found. anomalyFailureCount " + anomalyFailureCount + " and"
          + " anomalyWarningCount " + anomalyWarningCount);
    }
  }

  private void validateFunction(AnomalyFunctionDTO functionDTO) throws ValidationException {
    if (functionDTO == null) {
      throw new ValidationException("Couldn't find anomaly function.");
    }

    List<DatasetConfigDTO> datasetConfigDTOs = this.datasetConfigDAO.findByPredicate(Predicate.EQ("dataset", functionDTO.getCollection()));
    if (datasetConfigDTOs.isEmpty()) {
      throw new ValidationException("Dataset cannot be found for anomaly function " + functionDTO.getId());
    }

    List<MetricConfigDTO> metricConfigDTOs = this.metricConfigDAO.findByPredicate(Predicate.AND(
            Predicate.EQ("name", functionDTO.getMetric()),
            Predicate.EQ("dataset", functionDTO.getCollection())));
    if (metricConfigDTOs.isEmpty()) {
      throw new ValidationException("Metric cannot be found for anomaly function " + functionDTO.getId());
    }
  }

  Map<String, Object> translateAlertToYaml(AlertConfigDTO alertConfigDTO) {
    Map<String, Object> yamlConfigs = new LinkedHashMap<>();

    yamlConfigs.put(PROP_SUBS_GROUP_NAME, alertConfigDTO.getName());
    yamlConfigs.put(PROP_CRON, alertConfigDTO.getCronExpression());
    yamlConfigs.put(PROP_ACTIVE, alertConfigDTO.isActive());
    yamlConfigs.put(PROP_APPLICATION, alertConfigDTO.getApplication());
    yamlConfigs.put(PROP_EMAIL_SUBJECT_TYPE, alertConfigDTO.getSubjectType().name());
    yamlConfigs.put(PROP_FROM, alertConfigDTO.getFromAddress());

    yamlConfigs.put(PROP_TYPE, "DEFAULT_ALERTER_PIPELINE");

    Map<String, Object> recipients = new LinkedHashMap<>();
    recipients.put("to", ConfigUtils.getList(alertConfigDTO.getReceiverAddresses().getTo()));
    recipients.put("cc", ConfigUtils.getList(alertConfigDTO.getReceiverAddresses().getCc()));
    recipients.put("bcc", ConfigUtils.getList(alertConfigDTO.getReceiverAddresses().getBcc()));
    yamlConfigs.put(PROP_RECIPIENTS, recipients);

    List<Map<String, Object>> schemes = new ArrayList<>();
    Map<String, Object> emailScheme = new LinkedHashMap<>();
    emailScheme.put(PROP_TYPE, "EMAIL");
    schemes.add(emailScheme);
    yamlConfigs.put(PROP_ALERT_SCHEMES, schemes);

    List<String> detectionNames = new ArrayList<>();
    List<Long> detectionIds = alertConfigDTO.getEmailConfig().getFunctionIds();
    for (Long id : detectionIds) {
      List<AnomalyFunctionDTO> functionDTOS = this.anomalyFunctionDAO.findByPredicate(Predicate.EQ("baseId", id));
      if (functionDTOS.isEmpty()) {
        LOGGER.warn("[MIG] Anomaly function " + id + " cannot be found while generating notification YAML from legacy notification.");
        // Ignore missing anomaly functions
        continue;
      }

      String functionName = functionDTOS.get(0).getFunctionName();
      if (functionName.contains(MIGRATED_TAG)) {
        functionName = functionName.substring(0, functionName.lastIndexOf(MIGRATED_TAG));
      }
      detectionNames.add(functionName);
    }
    yamlConfigs.put(PROP_DETECTION_NAMES, detectionNames);

    return yamlConfigs;
  }

  @GET
  @Produces(MediaType.TEXT_PLAIN)
  @Consumes(MediaType.APPLICATION_JSON)
  @Path("/legacy-anomaly-function-to-yaml/{id}")
  public Response getYamlFromLegacyAnomalyFunction(@PathParam("id") long anomalyFunctionID) {
    AnomalyFunctionDTO anomalyFunctionDTO = this.anomalyFunctionDAO.findById(anomalyFunctionID);
    if (anomalyFunctionDTO == null) {
      return Response.status(Response.Status.BAD_REQUEST)
          .entity(ImmutableMap.of("message", "Legacy Anomaly function cannot be found for id "+ anomalyFunctionID))
          .build();
    }
    return Response.ok(this.yaml.dump(translateAnomalyFunctionToYaml(anomalyFunctionDTO))).build();
  }

  @GET
  @Produces(MediaType.TEXT_PLAIN)
  @Consumes(MediaType.APPLICATION_JSON)
  @Path("/legacy-alert-to-yaml/{id}")
  public Response getYamlFromLegacyAlert(@PathParam("id") long alertId) {
    AlertConfigDTO alertConfigDTO = this.alertConfigDAO.findById(alertId);
    if (alertConfigDTO == null) {
      return Response.status(Response.Status.BAD_REQUEST)
          .entity(ImmutableMap.of("message", "Legacy alert cannot be found for ID "+ alertId))
          .build();
    }
    return Response.ok(this.yaml.dump(translateAlertToYaml(alertConfigDTO))).build();
  }

  @POST
  @ApiOperation("migrate an application")
  @Path("/application/{name}")
  public Response migrateApplication(@PathParam("name") String application) {
    List<AlertConfigDTO> alertConfigDTOList = alertConfigDAO.findByPredicate(Predicate.EQ("application", application));
    Map<String, String> responseMessage = new HashMap<>();

    for (AlertConfigDTO alertConfigDTO : alertConfigDTOList) {
      try {
        migrateLegacyNotification(alertConfigDTO);
      } catch (Exception e) {
        // Skip migrating this alert and move on to the next
        LOGGER.error("[MIG] Failed to migrate alert ID {} name {}. Exception {}", alertConfigDTO.getId(), alertConfigDTO.getName(), e);
        responseMessage.put("Status of alert " + alertConfigDTO.getId(),
            String.format("Failed to migrate alert ID %d with name %s due to %s", alertConfigDTO.getId(),
                alertConfigDTO.getName(), e.getMessage()));
      }

    }

    if (responseMessage.isEmpty()) {
      return Response.ok("Application " + application + " has been successfully migrated").build();
    } else {
      return Response.status(Response.Status.OK).entity(responseMessage).build();
    }
  }

  @POST
  @Produces(MediaType.APPLICATION_JSON)
  @Consumes(MediaType.APPLICATION_JSON)
  @Path("/anomaly-function/{id}")
  public Response migrateAnomalyFunction(@PathParam("id") long anomalyFunctionId) throws Exception {
    return Response.ok(migrateLegacyAnomalyFunction(anomalyFunctionId)).build();
  }

  @POST
  @Produces(MediaType.APPLICATION_JSON)
  @Consumes(MediaType.APPLICATION_JSON)
  @Path("/unsubscribed-anomaly-functions")
  public Response migrateAnomalyFunction() throws Exception {
    List<AnomalyFunctionDTO> anomalyFunctionDTOs = anomalyFunctionDAO.findAll();
    Map<String, String> responseMessage = new HashMap<>();

    for (AnomalyFunctionDTO func : anomalyFunctionDTOs) {
      if (func.getFunctionName().contains(MIGRATED_TAG)) {
        LOGGER.info("[MIG] Function already migrated. Name " + func.getFunctionName());
        continue;
      }

      try {
        validateFunction(func);
      } catch (ValidationException e) {
        LOGGER.info("[MIG] Function failed validation. Name " + func.getFunctionName() + " Error : " + e.getMessage());
        responseMessage.put("Failed to migrate " + func.getId(),  String.format("Validation Error : %s", e.getMessage()));
        continue;
      }

      try {
        migrateLegacyAnomalyFunction(func);
      } catch (Exception e) {
        // Skip migrating this function and move on to the next
        responseMessage.put("Failed to migrate " + func.getId(), String.format("Error : %s", e.getMessage()));
      }
    }

    return Response.ok(responseMessage).build();
  }
}
