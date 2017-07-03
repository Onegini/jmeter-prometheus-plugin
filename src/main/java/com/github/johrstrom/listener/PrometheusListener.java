/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed  under the  License is distributed on an "AS IS" BASIS,
 * WITHOUT  WARRANTIES OR CONDITIONS  OF ANY KIND, either  express  or
 * implied.
 *
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.johrstrom.listener;

import static com.github.johrstrom.util.CollectorConfig.GET_SAMPLE_LABEL_METHOD_NAME;
import static com.github.johrstrom.util.CollectorConfig.GET_THREAD_NAME_METHOD_NAME;

import java.io.Serializable;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import org.apache.jmeter.assertions.AssertionResult;
import org.apache.jmeter.engine.util.NoThreadClone;
import org.apache.jmeter.reporters.AbstractListenerElement;
import org.apache.jmeter.samplers.Remoteable;
import org.apache.jmeter.samplers.SampleEvent;
import org.apache.jmeter.samplers.SampleListener;
import org.apache.jmeter.testelement.TestStateListener;
import org.apache.jmeter.testelement.property.ObjectProperty;
import org.apache.jmeter.threads.JMeterContextService;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.johrstrom.util.CollectorConfig;

import io.prometheus.client.CollectorRegistry;
import io.prometheus.client.Gauge;
import io.prometheus.client.Summary;
import io.prometheus.client.exporter.MetricsServlet;

/**
 * The main test element listener class of this library. Jmeter updates this
 * class through the SampleListener interface and it in turn updates the
 * CollectorRegistry. This class is also a TestStateListener to control when it
 * starts up or shuts down the server that ultimately serves Prometheus the
 * results through an http api.
 *
 * @author Jeff Ohrstrom
 */
public class PrometheusListener extends AbstractListenerElement
    implements SampleListener, Serializable, TestStateListener, Remoteable, NoThreadClone {

  public static final String SAVE_CONFIG = "johrstrom.save_config";

  private static final long serialVersionUID = -4833646252357876746L;

  private static final Logger log = LoggerFactory.getLogger(PrometheusListener.class);

  private Server server;

  // Samplers
  private Summary samplerCollector;
  private CollectorConfig samplerConfig = new CollectorConfig();
  private boolean collectSamples = true;
  private final String SCRIPT_SAMPLE_PREFIX = "[SCRIPT]";
  private final String DEBUG_SAMPLE_PREFIX = "Debug Sampler";
  private final String HELPER_SAMPLE_PREFIX = "HELPER";

  // Thread counter
  private Gauge threadCollector;
  private boolean collectThreads = true;

  // Assertions
  private Summary assertionsCollector;
  private CollectorConfig assertionConfig = new CollectorConfig();
  private boolean collectAssertions = true;

  /**
   * Default Constructor.
   */
  public PrometheusListener() {
    this(new PrometheusSaveConfig());
  }

  /**
   * Constructor with a configuration argument.
   *
   * @param config - the configuration to use.
   */
  public PrometheusListener(PrometheusSaveConfig config) {
    super();
    this.setSaveConfig(config);
    log.debug("Creating new prometheus listener.");
  }

  /*
   * (non-Javadoc)
   *
   * @see org.apache.jmeter.samplers.SampleListener#sampleOccurred(org.apache.
   * jmeter.samplers.SampleEvent)
   */
  public void sampleOccurred(SampleEvent event) {

    try {

      // build the label values from the event and observe the sampler
      // metrics
      final String[] samplerLabelValues = this.labelValues(event);
      final String samplerNameLabelValue = samplerLabelValues[0];
      if (notPerformanceIndicativeSample(samplerNameLabelValue)) {
        return;
      }
      if (collectSamples) {
        samplerCollector.labels(samplerLabelValues).observe(event.getResult().getTime());
      }

      if (collectThreads) {
        threadCollector.set(JMeterContextService.getContext().getThreadGroup().getNumberOfThreads());
      }

      // if there are any assertions to
      if (collectAssertions) {
        if (event.getResult().getAssertionResults().length > 0) {
          for (AssertionResult assertionResult : event.getResult().getAssertionResults()) {
            String[] assertionsLabelValues = this.labelValues(event, assertionResult);
            assertionsCollector.labels(assertionsLabelValues).observe(event.getResult().getTime());
          }
        }
      }

    } catch (IllegalAccessException | IllegalArgumentException | InvocationTargetException e) {
      log.error("Didn't update metric because of exception. Message was: {}", e.getMessage());
    }
  }

  private boolean notPerformanceIndicativeSample(final String samplerNameLabelValue) {
    return samplerNameLabelValue.startsWith(SCRIPT_SAMPLE_PREFIX)
        || samplerNameLabelValue.startsWith(DEBUG_SAMPLE_PREFIX)
        || samplerNameLabelValue.startsWith(HELPER_SAMPLE_PREFIX);
  }

  /*
   * (non-Javadoc)
   *
   * @see
   * org.apache.jmeter.samplers.SampleListener#sampleStarted(org.apache.jmeter
   * .samplers.SampleEvent)
   */
  public void sampleStarted(SampleEvent arg0) {
    // do nothing
  }

  /*
   * (non-Javadoc)
   *
   * @see
   * org.apache.jmeter.samplers.SampleListener#sampleStopped(org.apache.jmeter
   * .samplers.SampleEvent)
   */
  public void sampleStopped(SampleEvent arg0) {
    // do nothing
  }

  /*
   * (non-Javadoc)
   *
   * @see org.apache.jmeter.testelement.TestStateListener#testEnded()
   */
  public void testEnded() {
    try {
      this.server.stop();
    } catch (Exception e) {
      log.error("Couldn't stop http server", e);
    }
  }

  /*
   * (non-Javadoc)
   *
   * @see org.apache.jmeter.testelement.TestStateListener#testEnded(java.lang.
   * String)
   */
  public void testEnded(String arg0) {
    this.testEnded();
  }

  /*
   * (non-Javadoc)
   *
   * @see org.apache.jmeter.testelement.TestStateListener#testStarted()
   */
  public void testStarted() {
    // update the configuration
    this.reconfigure();
    this.server = new Server(this.getSaveConfig().getPort());

    ServletContextHandler context = new ServletContextHandler();
    context.setContextPath("/");
    server.setHandler(context);
    context.addServlet(new ServletHolder(new MetricsServlet()), "/metrics");

    try {
      server.start();
    } catch (Exception e) {
      log.error("Couldn't start http server", e);
    }

  }

  /**
   * Set a new Save configuration. Note that this function reconfigures this
   * object and one should not set the save config directly through
   * {@link #setProperty(org.apache.jmeter.testelement.property.JMeterProperty)}
   * functions.
   *
   * @param config - the configuration object
   */
  public void setSaveConfig(PrometheusSaveConfig config) {
    this.setProperty(new ObjectProperty(SAVE_CONFIG, config));
    this.reconfigure();
  }

  /**
   * Get the current Save configuration
   */
  public PrometheusSaveConfig getSaveConfig() {
    return (PrometheusSaveConfig) this.getProperty(SAVE_CONFIG).getObjectValue();
  }

  /*
   * (non-Javadoc)
   *
   * @see
   * org.apache.jmeter.testelement.TestStateListener#testStarted(java.lang.
   * String)
   */
  public void testStarted(String arg0) {
    this.testStarted();
  }

  /**
   * For a given SampleEvent, get all the label values as determined by the
   * configuration. Can return reflection related errors because this invokes
   * SampleEvent accessor methods like getResponseCode or getSuccess.
   *
   * @param event - the event that occurred
   */
  protected String[] labelValues(SampleEvent event)
      throws IllegalAccessException, IllegalArgumentException, InvocationTargetException {

    String[] values = new String[this.samplerConfig.getLabels().length];

    // last value (category label) is set in setSamplerNameAndCategory method along with sampler name
    for (int i = 0; i < values.length - 1; i++) {
      Method m = this.samplerConfig.getMethods()[i];
      final String invokeMethodResult = m.invoke(event.getResult()).toString();
      if (m.getName().equals(GET_THREAD_NAME_METHOD_NAME)) {
        setNumberOfUsers(values, i, invokeMethodResult);
      } else if (m.getName().equals(GET_SAMPLE_LABEL_METHOD_NAME)) {
        setSamplerNameAndCategory(values, i, invokeMethodResult);
      } else {
        values[i] = invokeMethodResult;
      }
    }

    return values;
  }

  private void setNumberOfUsers(final String[] values, final int i, final String invokeMethodResult) {
    final String[] threadNameParts = invokeMethodResult.split("/", 2);
    values[i] = threadNameParts[0].substring(1);
  }

  private void setSamplerNameAndCategory(final String[] values, final int i, final String invokeMethodResult) {
    final String[] samplerNameLabelParts = invokeMethodResult.split("] ", 2);
    if(samplerNameLabelParts.length == 1){
      //set sampler name
      values[i] = samplerNameLabelParts[0];
      //category not defined
      values[values.length - 1] = "TODO";
    }
    //set sampler name
    values[i] = samplerNameLabelParts[1];
    //set category
    values[values.length - 1] = samplerNameLabelParts[0].substring(1);
  }

  /**
   * For a given SampleEvent and AssertionResult, get all the label values as
   * determined by the configuration. Can return reflection related errors
   * because this invokes SampleEvent accessor methods like getResponseCode or
   * getSuccess.
   *
   * @param event           - the event that occurred
   * @param assertionResult - the assertion results associated to the event
   */
  protected String[] labelValues(SampleEvent event, AssertionResult assertionResult)
      throws IllegalAccessException, IllegalArgumentException, InvocationTargetException {

    String[] values = new String[this.assertionConfig.getLabels().length];

    for (int i = 0; i < values.length; i++) {
      Method m = this.assertionConfig.getMethods()[i];
      if (m.getDeclaringClass().equals(AssertionResult.class)) {
        values[i] = m.invoke(assertionResult).toString();
      } else {
        values[i] = m.invoke(event.getResult()).toString();
      }
    }

    return values;

  }

  /**
   * Helper function to modify private member collectors and collector
   * configurations. Any invocation of this method will modify them, even if
   * configuration fails due to reflection errors, default configurations are
   * applied and new collectors created.
   */
  protected void reconfigure() {

    CollectorConfig tmpAssertConfig = new CollectorConfig();
    CollectorConfig tmpSamplerConfig = new CollectorConfig();

    // activate collections
    collectSamples = this.getSaveConfig().saveSuccess() || this.getSaveConfig().saveCode()
        || this.getSaveConfig().saveLabel();
    collectThreads = this.getSaveConfig().saveThreads();
    collectAssertions = this.getSaveConfig().saveAssertions();

    try {
      // try to build new config objects
      tmpAssertConfig = this.newAssertionCollectorConfig();
      tmpSamplerConfig = this.newSamplerCollectorConfig();

    } catch (NoSuchMethodException | SecurityException e) {
      log.error("Only partial reconfigure due to exception.", e);
    }

    // remove old collectors and reassign member variables
    CollectorRegistry.defaultRegistry.clear();
    this.assertionConfig = tmpAssertConfig;
    this.samplerConfig = tmpSamplerConfig;

    // register new collectors
    if (collectSamples) {
      this.samplerCollector = Summary.build().name("jmeter_samples_latency").help("Summary for Sample Latency")
          .labelNames(this.samplerConfig.getLabels()).quantile(0.5, 0.1).quantile(0.99, 0.1).create()
          .register(CollectorRegistry.defaultRegistry);
    }

    if (collectThreads) {
      this.threadCollector = Gauge.build().name("jmeter_running_threads").help("Counter for running threds")
          .create().register(CollectorRegistry.defaultRegistry);
    }

    if (collectAssertions) {
      this.assertionsCollector = Summary.build().name("jmeter_assertions_total").help("Counter for assertions")
          .labelNames(this.assertionConfig.getLabels()).create().register(CollectorRegistry.defaultRegistry);
    }

    log.info("Reconfigure complete.");

    if (log.isDebugEnabled()) {
      log.debug("Assertion Configuration: " + this.assertionConfig.toString());
      log.debug("Sampler Configuration: " + this.samplerConfig.toString());
    }

  }

  /**
   * Create a new CollectorConfig for Samplers. Due to reflection this throws
   * errors based on security and absence of method definitions.
   *
   * @return the new CollectorConfig
   */
  protected CollectorConfig newSamplerCollectorConfig() throws NoSuchMethodException, SecurityException {
    PrometheusSaveConfig saveConfig = this.getSaveConfig();
    CollectorConfig collectorConfig = new CollectorConfig();

    if (saveConfig.saveLabel()) {
      collectorConfig.saveSamplerLabel();
      collectorConfig.saveCategoryLabel();
    }

    if (saveConfig.saveCode()) {
      collectorConfig.saveSamlerCode();
    }

    if (saveConfig.saveSuccess()) {
      collectorConfig.saveSamplerSuccess();
    }

    if (saveConfig.saveThreads()) {
      collectorConfig.saveThreadName();
    }

    return collectorConfig;
  }

  /**
   * Create a new CollectorConfig for Assertions. Due to reflection this
   * throws errors based on security and absence of method definitions.
   */
  protected CollectorConfig newAssertionCollectorConfig() throws NoSuchMethodException, SecurityException {
    PrometheusSaveConfig saveConfig = this.getSaveConfig();
    CollectorConfig collectorConfig = new CollectorConfig();

    if (saveConfig.saveAssertions()) {
      // TODO configure assertions more granularly
      collectorConfig.saveSamplerLabel();
      collectorConfig.saveAssertionFailure();
      collectorConfig.saveAssertionName();
    }

    return collectorConfig;
  }

}
