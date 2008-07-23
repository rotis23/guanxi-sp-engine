//: "The contents of this file are subject to the Mozilla Public License
//: Version 1.1 (the "License"); you may not use this file except in
//: compliance with the License. You may obtain a copy of the License at
//: http://www.mozilla.org/MPL/
//:
//: Software distributed under the License is distributed on an "AS IS"
//: basis, WITHOUT WARRANTY OF ANY KIND, either express or implied. See the
//: License for the specific language governing rights and limitations
//: under the License.
//:
//: The Original Code is Guanxi (http://www.guanxi.uhi.ac.uk).
//:
//: The Initial Developer of the Original Code is Alistair Young alistair@codebrane.com
//: All Rights Reserved.
//:

package org.guanxi.sp.engine;

import org.springframework.web.context.ServletContextAware;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.ContextStartedEvent;
import org.springframework.context.event.ContextStoppedEvent;
import org.apache.log4j.Logger;
import org.apache.xmlbeans.XmlException;
import org.guanxi.common.log.Log4JLoggerConfig;
import org.guanxi.common.log.Log4JLogger;
import org.guanxi.common.metadata.IdPMetadataManager;
import org.guanxi.common.metadata.IdPMetadataImpl;
import org.guanxi.common.GuanxiException;
import org.guanxi.common.job.GuanxiJobConfig;
import org.guanxi.common.security.SecUtils;
import org.guanxi.common.definitions.Guanxi;
import org.guanxi.xal.saml_2_0.metadata.EntityDescriptorDocument;
import org.guanxi.xal.saml_2_0.metadata.EntityDescriptorType;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.quartz.*;
import org.quartz.impl.StdSchedulerFactory;

import javax.servlet.ServletContext;
import java.security.Security;
import java.security.Provider;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.ParseException;

public class Bootstrap implements ApplicationListener, ApplicationContextAware, ServletContextAware {
  /** Our logger */
  private Logger log = null;
  /** The logger config */
  private Log4JLoggerConfig loggerConfig = null;
  /** The Logging setup to use */
  private Log4JLogger logger = null;
  /** Spring ApplicationContext */
  @SuppressWarnings("unused")
  private ApplicationContext applicationContext = null;
  /** The servlet context */
  private ServletContext servletContext = null;
  /** Holds all the IdP X509 certificates and their chains loaded from the metadata directory. This
   * is what's used to verify an IdP's AuthenticationStatement for example */
  private X509Chain x509Chain = null;
  /** Our configuration */
  private Config config = null;
  /** If this instance of an Engine loads the BouncyCastle security provider then it should unload it */
  private boolean okToUnloadBCProvider = false;
  /** The background jobs to start */
  private GuanxiJobConfig[] gxJobs = null;

  /**
   * Initialise the intercepter
   */
  public void init() {
    try {
      File keyStoreFile, trustStoreFile;
      
      loggerConfig.setClazz(Bootstrap.class);

      // Sort out the file paths for logging
      loggerConfig.setLogConfigFile(servletContext.getRealPath(loggerConfig.getLogConfigFile()));
      loggerConfig.setLogFile(servletContext.getRealPath(loggerConfig.getLogFile()));

      // Get our logger
      log = logger.initLogger(loggerConfig);

      /* If we try to add the BouncyCastle provider but another Guanxi::SP running
       * in another webapp in the same container has already done so, then we'll get
       * -1 returned from the method, in which case, we should leave unloading of the
       * provider to the particular Guanxi::SP that loaded it.
       */
      if ((Security.addProvider(new BouncyCastleProvider())) != -1) {
        // We've loaded it, so we should unload it
        okToUnloadBCProvider = true;
      }

      // If we don't have a keystore, create a self signed one now
      keyStoreFile = new File(config.getKeystore());
      if (!keyStoreFile.exists()) {
        try {
          SecUtils secUtils = SecUtils.getInstance();
          secUtils.createSelfSignedKeystore(config.getId(), // cn
                                            config.getKeystore(),
                                            config.getKeystorePassword(),
                                            config.getKeystorePassword(),
                                            config.getCertificateAlias());
        }
        catch(GuanxiException ge) {
          log.error("Can't create self signed keystore - secure Guard comms won't be available : ", ge);
        }
      }

      // Create a truststore if we don't have one
      trustStoreFile = new File(config.getTrustStore());
      if (!trustStoreFile.exists()) {
        try {
          SecUtils secUtils = SecUtils.getInstance();
          secUtils.createTrustStore(config.getTrustStore(),
                                    config.getTrustStorePassword());
        }
        catch(GuanxiException ge) {
          log.error("Can't create truststore - secure comms won't be available : ", ge);
        }
      }

      loadGuardMetadata(config.getGuardsMetadataDirectory());
      loadIdPMetadata(config.getIdPMetadataDirectory());

      x509Chain = new X509Chain(config.getIdPMetadataDirectory());

      startJobs();
    }
    catch(GuanxiException ge) {
      log.error("Issue during the initialization of the Bootstrap : ", ge);
    }
  }

  /**
   * Called by Spring when application events occur. At the moment we handle:
   * ContextClosedEvent
   * ContextRefreshedEvent
   * RequestHandledEvent
   *
   * This is where we inject the job controllers into the application context, each one
   * under it's own key.
   * 
   * To understand the different events see
   * http://static.springframework.org/spring/docs/2.5.x/reference/beans.html#context-functionality-events
   *
   * @param applicationEvent Spring application event
   */
  public void onApplicationEvent(ApplicationEvent applicationEvent) {
    
    /* 
     * ContextClosedEvent
     * Published when the ApplicationContext is closed, using the 
     * close() method on the ConfigurableApplicationContext  
     * interface. "Closed" here means that all singleton beans are 
     * destroyed. A closed context has reached its end of life; it 
     * cannot be refreshed or restarted.
     */
    if ( applicationEvent instanceof ContextClosedEvent ) {
      if (okToUnloadBCProvider) {
        Provider[] providers = Security.getProviders();

        /* Although addProvider() returns the ID of the newly installed provider,
         * we can't rely on this. If another webapp removes a provider from the list of
         * installed providers, all the other providers shuffle up the list by one, thus
         * invalidating the ID we got from addProvider().
         */
        try {
          for (int i=0; i < providers.length; i++) {
            if (providers[i].getName().equalsIgnoreCase(Guanxi.BOUNCY_CASTLE_PROVIDER_NAME)) {
              Security.removeProvider(Guanxi.BOUNCY_CASTLE_PROVIDER_NAME);
            }
          }
        }
        catch(SecurityException se) {
          /* We'll end up here if a security manager is installed and it refuses us
           * permission to remove the BouncyCastle provider
           */
        }
      }
    }
    
    /*
     * ContextRefreshedEvent  
     * Published when the ApplicationContext is initialised or 
     * refreshed, e.g. using the refresh() method on the 
     * ConfigurableApplicationContext interface. "Initialised" 
     * here means that all beans are loaded, post-processor 
     * beans are detected and activated, singletons are 
     * pre-instantiated, and the ApplicationContext object is 
     * ready for use. A refresh may be triggered multiple times, 
     * as long as the context hasn't been closed - provided that 
     * the chosen ApplicationContext  actually supports such 
     * "hot" refreshes (which e.g. XmlWebApplicationContext does 
     * but GenericApplicationContext doesn't).
     */
    else if ( applicationEvent instanceof ContextRefreshedEvent ) {
      // Advertise the application as available for business
      servletContext.setAttribute(Guanxi.CONTEXT_ATTR_ENGINE_CONFIG, config);

      // Add the X509 stuff to the context
      servletContext.setAttribute(Guanxi.CONTEXT_ATTR_X509_CHAIN, x509Chain);
      
      log.info("init : " + config.getId());
    }
    
    /*
     * ContextStartedEvent  
     * Published when the ApplicationContext is started, using the 
     * start() method on the ConfigurableApplicationContext 
     * interface. "Started" here means that all life cycle beans will 
     * receive an explicit start signal. This will typically be used 
     * for restarting after an explicit stop, but may also be used 
     * for starting components that haven't been configured for 
     * auto start (e.g. haven't started on initialisation already).
     */
    else if ( applicationEvent instanceof ContextStartedEvent ) {
      try {
        loadMetadata();
      }
      catch ( Exception e ) {
        log.error("Unable to load cached metadata", e);
      }
    }
    
    /*
     * ContextStoppedEvent  
     * Published when the ApplicationContext is stopped, using the 
     * stop() method on the ConfigurableApplicationContext interface. 
     * "Stopped" here means that all life cycle beans will receive an 
     * explicit stop signal. A stopped context may be restarted through 
     * a start() call.
     */
    else if ( applicationEvent instanceof ContextStoppedEvent ) {
      try {
        saveMetadata();
      }
      catch ( Exception e ) {
        log.error("Unable to save metadata to cache", e);
      }
    }
  }
  
  /**
   * This will load the cached metadata if it has been saved.
   * This means that the webapp can restore the loaded metadata
   * even if the metadata sources are not available on startup.
   * 
   * @throws IOException  If there is a problem reading the cache file.
   * @throws XmlException If there is a problem parsing the XML in the cache file.
   */
  private void loadMetadata() throws IOException, XmlException {
    File metadata_file;
    
    metadata_file = new File(config.getMetadataCacheFile());
    if ( metadata_file.exists() ) {
      InputStream in;
      
      in = new FileInputStream(metadata_file);
      try {
        IdPMetadataManager.getManager(servletContext).read(in);
      }
      finally {
        in.close();
      }
    }
  }
  
  /**
   * This will save the metadata to a file for later loading when
   * the application starts. This allows the loaded metadata to be
   * restored even if the metadata sources are not available when
   * the application starts up.
   * 
   * @throws IOException  If there is a problem writing to the cache file.
   */
  private void saveMetadata() throws IOException {
    File metadata_file;
    
    metadata_file = new File(config.getMetadataCacheFile());
    if ( metadata_file.exists() ) {
      OutputStream out;
      
      out = new FileOutputStream(metadata_file);
      try {
        IdPMetadataManager.getManager(servletContext).write(out);
      }
      finally {
        out.close();
      }
    }
  }

  /**
   * Loads Guard metadata from:
   * WEB-INF/config/metadata/guards
   * If an error occurs the Guard will be ignored.
   *
   * Guard metadata files are in SAML2 format and are named after their containing directory:
   * WEB-INF/config/metadata/guards/protectedapp/protectedapp.xml
   *
   * If a Guard's metadata file is not named after it's containing directory it will be ignored.
   * Normally these directories and metadata files are created by the Engine from the guard request
   * page:
   * /guanxi_sp/request_guard.jsp
   *
   * @param guardsMetadataDir The full path and name of the directory containing the Guard metadata files
   */
  private void loadGuardMetadata(String guardsMetadataDir) {
    /**
     * Looks for directories during a search
     * This has been moved inside this method because
     * it is not referenced anywhere else.
     */
    class DirFileFilter implements FilenameFilter {
      public boolean accept(File file, String name) {
        return file.isDirectory();
      }
    }
    
    File[] guardDirectories;
    int loaded;
    
    guardDirectories = new File(guardsMetadataDir).listFiles(new DirFileFilter());

    loaded = 0;
    for ( File currentGuardDirectory : guardDirectories ) {
      File currentGuardFile;
      
      currentGuardFile = new File(currentGuardDirectory, currentGuardDirectory.getName() + ".xml");
      
      try {
        EntityDescriptorDocument guardDocument;
        EntityDescriptorType guardDescriptor;

        // Load up the SAML2 metadata for the Guard
        guardDocument = EntityDescriptorDocument.Factory.parse(currentGuardFile);
        guardDescriptor = guardDocument.getEntityDescriptor();

        // Put the Guard's SAML2 EntityDescriptor in the context under the Guard's entityID
        // TODO: This should probably be handled in a similar way to the IdP metadata
        servletContext.setAttribute(guardDescriptor.getEntityID(), guardDescriptor);
        loaded++;
      }
      catch ( Exception e ) {
        // If we get here then the Engine won't know anything about the Guard
        log.error("Error while loading Guard metadata : " + currentGuardFile.getAbsolutePath(), e);
      }
    }

    log.info("Loaded " + loaded + " of " + guardDirectories.length + " Guard metadata objects");
  } // loadGuardMetadata

  /**
   * Loads IdP metadata from:
   * WEB-INF/config/metadata/idp
   *
   * @param idpMetadataDir The full path and name of the directory containing the IdP metadata files
   * @throws GuanxiException if an error occurs
   */
  private void loadIdPMetadata(String idpMetadataDir) throws GuanxiException {
    /**
     * Looks for XML files during a search.
     * This has been moved inside this method because it
     * is not referenced anywhere else.
     */
    class XMLFileFilter implements FilenameFilter {
      public boolean accept(File file, String name) {
        return name.endsWith(".xml");
      }
    }
    
    File[] idpFiles;
    
    idpFiles = new File(idpMetadataDir).listFiles(new XMLFileFilter());
    
    for ( File currentIdPFile : idpFiles ) {
      try {
        EntityDescriptorDocument idpDocument;
        EntityDescriptorType idpDescriptor;
        
        idpDocument = EntityDescriptorDocument.Factory.parse(currentIdPFile);
        idpDescriptor = idpDocument.getEntityDescriptor();
        
        IdPMetadataManager.getManager(servletContext).setMetadata(currentIdPFile.getCanonicalPath(), new IdPMetadataImpl(idpDescriptor));
      }
      catch ( Exception e ) {
        log.error("Error while loading IdP metadata object : " + currentIdPFile.getAbsolutePath(), e);
        throw new GuanxiException(e);
      }
    }
    log.info("Loaded " + idpFiles.length + " IdP metadata objects");
  } // loadIdPMetadata

  /**
   * This starts the jobs that are associated with this webapp.
   * The jobs will be performed immediately if they have startImmediately
   * set to true, otherwise they will be performed when the cron line
   * indicates.
   */
  private void startJobs() {
    try {
      // Get a new scheduler
      Scheduler scheduler = new StdSchedulerFactory().getScheduler();
      // Start it up. This won't start any jobs though.
      scheduler.start();

      for (GuanxiJobConfig gxJob : gxJobs) {
        log.info("Registering job : " + gxJob.getKey() + " : " + gxJob.getJobClass());

        // Need a new JobDetail to hold custom data to send to the job we're controlling
        JobDetail jobDetail = new JobDetail(gxJob.getKey(), Scheduler.DEFAULT_GROUP, Class.forName(gxJob.getJobClass()));

        // Create a new JobDataMap for custom data to be sent to the job...
        JobDataMap jobDataMap = new JobDataMap();
        // ...and add the job's custom config object
        jobDataMap.put(GuanxiJobConfig.JOB_KEY_JOB_CONFIG, gxJob);

        // Put the job's custom data in it's JobDetail
        jobDetail.setJobDataMap(jobDataMap);

        /* Tell the scheduler when this job will run. Nothing will happen
         * until the start method is called.
         */
        Trigger trigger = new CronTrigger(gxJob.getKey(), Scheduler.DEFAULT_GROUP, gxJob.getCronLine());

        // Start the job
        scheduler.scheduleJob(jobDetail, trigger);

        if (gxJob.isStartImmediately()) {
          scheduler.triggerJob(gxJob.getKey(), Scheduler.DEFAULT_GROUP);
        }
      }
    }
    catch(ClassNotFoundException cnfe) {
      log.error("Error locating job class", cnfe);
    }
    catch(SchedulerException se) {
      log.error("Job scheduling error", se);
    }
    catch(ParseException pe) {
      log.error("Error parsing job cronline", pe);
    }
  }
  
  // all of the getters and setters can be found below here

  /**
   * Called by Spring to give us the ApplicationContext
   *
   * @param applicationContext Spring ApplicationContext
   * @throws org.springframework.beans.BeansException
   */
  public void setApplicationContext(ApplicationContext applicationContext) throws org.springframework.beans.BeansException {
    this.applicationContext = applicationContext;
  }

  /**
   * Sets the servlet context
   * @param servletContext The servlet context
   */
  public void setServletContext(ServletContext servletContext) {
    this.servletContext = servletContext;
  }

  /**
   * Sets the logger configuration
   * @param loggerConfig
   */
  public void setLoggerConfig(Log4JLoggerConfig loggerConfig) { 
    this.loggerConfig = loggerConfig; 
  }
  /**
   * Returns the logger configuration
   * @return
   */
  public Log4JLoggerConfig getLoggerConfig() { 
    return loggerConfig; 
  }

  /**
   * Sets the logger
   * @param logger
   */
  public void setLogger(Log4JLogger logger) { 
    this.logger = logger; 
  }
  /**
   * Returns the logger
   * @return
   */
  public Log4JLogger getLogger() { 
    return logger; 
  }

  /**
   * Sets the bootstrap configuration
   * @param config
   */
  public void setConfig(Config config) { 
    this.config = config; 
  }
  /**
   * Gets the bootstrap configuration
   * @return
   */
  public Config getConfig() { 
    return config; 
  }

  /**
   * This sets the jobs that will be periodically performed.
   * @param gxJobs
   */
  public void setGxJobs(GuanxiJobConfig[] gxJobs) { 
    this.gxJobs = gxJobs; 
  }
}
