/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.openejb.cdi;

import org.apache.openejb.AppContext;
import org.apache.openejb.BeanContext;
import org.apache.openejb.OpenEJBRuntimeException;
import org.apache.openejb.assembler.classic.AppInfo;
import org.apache.openejb.assembler.classic.Assembler;
import org.apache.openejb.loader.SystemInstance;
import org.apache.openejb.util.LogCategory;
import org.apache.openejb.util.Logger;
import org.apache.webbeans.config.OpenWebBeansConfiguration;
import org.apache.webbeans.config.WebBeansContext;
import org.apache.webbeans.config.WebBeansFinder;
import org.apache.webbeans.container.BeanManagerImpl;
import org.apache.webbeans.intercept.InterceptorResolutionService;
import org.apache.webbeans.portable.InjectionTargetImpl;
import org.apache.webbeans.portable.events.discovery.BeforeShutdownImpl;
import org.apache.webbeans.spi.ContainerLifecycle;
import org.apache.webbeans.spi.ContextsService;
import org.apache.webbeans.spi.JNDIService;
import org.apache.webbeans.spi.ResourceInjectionService;
import org.apache.webbeans.spi.ScannerService;
import org.apache.webbeans.spi.adaptor.ELAdaptor;
import org.apache.webbeans.util.WebBeansConstants;
import org.apache.webbeans.util.WebBeansUtil;
import org.apache.webbeans.xml.WebBeansXMLConfigurator;

import javax.el.ELResolver;
import javax.enterprise.inject.spi.BeanManager;
import javax.servlet.ServletContext;
import javax.servlet.ServletContextEvent;
import javax.servlet.jsp.JspApplicationContext;
import javax.servlet.jsp.JspFactory;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * @version $Rev:$ $Date:$
 */
public class OpenEJBLifecycle implements ContainerLifecycle {
    public static final ThreadLocal<AppInfo> CURRENT_APP_INFO = new ThreadLocal<AppInfo>();

    //Logger instance
    private static final Logger logger = Logger.getInstance(LogCategory.OPENEJB_CDI, OpenEJBLifecycle.class);

    public static final String OPENEJB_CDI_SKIP_CLASS_NOT_FOUND = "openejb.cdi.skip-class-not-found";

    /**Discover bean classes*/
    protected ScannerService scannerService;

    protected final ContextsService contextsService;

    private final boolean skipClassNotFoundError;

    /**Deploy discovered beans*/
    private final org.apache.webbeans.config.BeansDeployer deployer;

    /**XML discovery. */
    //XML discovery is removed from the specification. It is here for next revisions of spec.
    private final WebBeansXMLConfigurator xmlDeployer;

    /**Using for lookup operations*/
    private final JNDIService jndiService;

    /**Root container.*/
    private final BeanManagerImpl beanManager;
    private final WebBeansContext webBeansContext;
    /**Manages unused conversations*/
    private ScheduledExecutorService service = null;

    public OpenEJBLifecycle(WebBeansContext webBeansContext)
    {
        this.webBeansContext = webBeansContext;
        beforeInitApplication(null);

        this.beanManager = webBeansContext.getBeanManagerImpl();
        this.xmlDeployer = new WebBeansXMLConfigurator();
        this.deployer = new org.apache.webbeans.config.BeansDeployer(this.xmlDeployer, webBeansContext);
        this.jndiService = webBeansContext.getService(JNDIService.class);
        this.beanManager.setXMLConfigurator(this.xmlDeployer);
        this.scannerService = webBeansContext.getScannerService();
        this.contextsService = webBeansContext.getContextsService();

        this.skipClassNotFoundError = SystemInstance.get().getOptions().get(OPENEJB_CDI_SKIP_CLASS_NOT_FOUND, false);

        initApplication(null);
    }

    @Override
    public BeanManager getBeanManager()
    {
        return this.beanManager;
    }

    @Override
    public void startApplication(Object startupObject)
    {
        if (startupObject instanceof ServletContextEvent) {
            startServletContext((ServletContext) getServletContext(startupObject)); // TODO: check it is relevant
            return;
        } else if (!(startupObject instanceof StartupObject)) {
            logger.debug("startupObject is not of StartupObject type; ignored");
            return;
        }

        StartupObject stuff = (StartupObject) startupObject;
        final ClassLoader oldCl = Thread.currentThread().getContextClassLoader();

        // Initalize Application Context
        logger.info("OpenWebBeans Container is starting...");

        long begin = System.currentTimeMillis();

        try {
            Thread.currentThread().setContextClassLoader(stuff.getClassLoader());

            //Before Start
            beforeStartApplication(startupObject);


            //Load all plugins
            webBeansContext.getPluginLoader().startUp();

            //Get Plugin
            CdiPlugin cdiPlugin = (CdiPlugin) webBeansContext.getPluginLoader().getEjbPlugin();

            final AppContext appContext = stuff.getAppContext();
            if (stuff.getWebContext() == null) {
                appContext.setWebBeansContext(webBeansContext);
            }

            cdiPlugin.setClassLoader(stuff.getClassLoader());
            cdiPlugin.setWebBeansContext(webBeansContext);
            cdiPlugin.startup();

            //Configure EJB Deployments
            cdiPlugin.configureDeployments(stuff.getBeanContexts());

            //Resournce Injection Service
            CdiResourceInjectionService injectionService = (CdiResourceInjectionService) webBeansContext.getService(ResourceInjectionService.class);
            injectionService.setAppContext(stuff.getAppContext());

            //Deploy the beans
            try {
                //Load Extensions
                webBeansContext.getExtensionLoader().loadExtensionServices(Thread.currentThread().getContextClassLoader()); // init in OpenEJBLifecycle

                //Initialize contexts
                this.contextsService.init(startupObject);

                //Scanning process
                logger.debug("Scanning classpaths for beans artifacts.");

                if (scannerService instanceof CdiScanner) {
                    final CdiScanner service = (CdiScanner) scannerService;
                    service.init(startupObject);
                } else {
                    new CdiScanner().init(startupObject);
                }

                //Scan
                this.scannerService.scan();

                // just to let us write custom CDI Extension using our internals easily
                CURRENT_APP_INFO.set(StartupObject.class.cast(startupObject).getAppInfo());

                //Deploy bean from XML. Also configures deployments, interceptors, decorators.
                deployer.deploy(scannerService);
            } catch (Exception e1) {
                Assembler.logger.error("CDI Beans module deployment failed", e1);
                throw new OpenEJBRuntimeException(e1);
            } finally {
                CURRENT_APP_INFO.remove();
            }

            for (final BeanContext bc : stuff.getBeanContexts()) {
                final CdiEjbBean cdiEjbBean = bc.get(CdiEjbBean.class);
                if (cdiEjbBean == null) {
                    continue;
                }

                cdiEjbBean.defineBeanInterceptorStack();
                bc.mergeOWBAndOpenEJBInfo();
                bc.set(InterceptorResolutionService.BeanInterceptorInfo.class, InjectionTargetImpl.class.cast(cdiEjbBean.getInjectionTarget()).getInterceptorInfo());
            }

            //Start actual starting on sub-classes
            afterStartApplication(startupObject);
        } finally {
            Thread.currentThread().setContextClassLoader(oldCl);

            // cleanup threadlocal used to enrich cdi context manually
            OptimizedLoaderService.ADDITIONAL_EXTENSIONS.remove();
            CdiScanner.ADDITIONAL_CLASSES.remove();
        }

        logger.info("OpenWebBeans Container has started, it took {0} ms.", Long.toString(System.currentTimeMillis() - begin));
    }

    private static boolean rootCauseIsClassNotFound(final RuntimeException re) {
        Throwable e = re;
        e.getStackTrace();
        while (e != null) {
            if (e instanceof ClassNotFoundException) {
                return true;
            }
            e = e.getCause();
        }
        return false;
    }

    @Override
    public void stopApplication(Object endObject)
    {
        logger.debug("OpenWebBeans Container is stopping.");

        try
        {
            //Sub-classes operations
            beforeStopApplication(null);

            //Fire shut down
            if (beanManager instanceof WebappBeanManager) {
                ((WebappBeanManager) beanManager).beforeStop();
            }
            this.beanManager.fireEvent(new BeforeShutdownImpl());

            //Destroys context
            this.contextsService.destroy(null);

            //Unbind BeanManager
            if (jndiService != null) {
                jndiService.unbind(WebBeansConstants.WEB_BEANS_MANAGER_JNDI_NAME);
            }

            //Free all plugin resources
            ((CdiPlugin) webBeansContext.getPluginLoader().getEjbPlugin()).clearProxies();
            webBeansContext.getPluginLoader().shutDown();

            //Clear extensions
            webBeansContext.getExtensionLoader().clear();

            //Delete Resolutions Cache
            beanManager.getInjectionResolver().clearCaches();

            //Delete AnnotateTypeCache
            webBeansContext.getAnnotatedElementFactory().clear();

            //After Stop
            afterStopApplication(null);

            // Clear BeanManager
            this.beanManager.clear();

            // Clear singleton list
            WebBeansFinder.clearInstances(WebBeansUtil.getCurrentClassLoader());

        }
        catch (Exception e)
        {
            logger.error("An error occured while stopping the container.", e);
        }

    }

    /**
     * @return the scannerService
     */
    protected ScannerService getScannerService()
    {
        return scannerService;
    }

    /**
     * @return the contextsService
     */
    public ContextsService getContextService()
    {
        return contextsService;
    }

    /**
     * @return the xmlDeployer
     */
    protected WebBeansXMLConfigurator getXmlDeployer()
    {
        return xmlDeployer;
    }

    /**
     * @return the jndiService
     */
    protected JNDIService getJndiService()
    {
        return jndiService;
    }

    @Override
    public void initApplication(Properties properties)
    {
        afterInitApplication(properties);
    }

    protected void beforeInitApplication(Properties properties)
    {
        //Do nothing as default
    }

    protected void afterInitApplication(Properties properties) {
        //Do nothing as default
    }

    protected void afterStartApplication(final Object startupObject) {
        if (beanManager instanceof WebappBeanManager) {
            ((WebappBeanManager) beanManager).afterStart();
        }
    }

    public void startServletContext(final ServletContext servletContext) {
        service = initializeServletContext(servletContext, webBeansContext);
    }

    public static ScheduledExecutorService initializeServletContext(final ServletContext servletContext, WebBeansContext context) {
        String strDelay = context.getOpenWebBeansConfiguration().getProperty(OpenWebBeansConfiguration.CONVERSATION_PERIODIC_DELAY, "150000");
        long delay = Long.parseLong(strDelay);

        final ScheduledExecutorService executorService = Executors.newScheduledThreadPool(1, new ThreadFactory() {
            @Override
            public Thread newThread(Runnable runable) {
                Thread t = new Thread(runable, "OwbConversationCleaner-" + servletContext.getContextPath());
                t.setDaemon(true);
                return t;
            }
        });
        executorService.scheduleWithFixedDelay(new ConversationCleaner(context), delay, delay, TimeUnit.MILLISECONDS);

        ELAdaptor elAdaptor = context.getService(ELAdaptor.class);
        ELResolver resolver = elAdaptor.getOwbELResolver();
        //Application is configured as JSP
        if (context.getOpenWebBeansConfiguration().isJspApplication()) {
            logger.debug("Application is configured as JSP. Adding EL Resolver.");

            JspFactory factory = JspFactory.getDefaultFactory();
            if (factory != null) {
                JspApplicationContext applicationCtx = factory.getJspApplicationContext(servletContext);
                applicationCtx.addELResolver(resolver);
            } else {
                logger.debug("Default JspFactory instance was not found");
            }
        }

        // Add BeanManager to the 'javax.enterprise.inject.spi.BeanManager' servlet context attribute
        servletContext.setAttribute(BeanManager.class.getName(), context.getBeanManagerImpl());

        return executorService;
    }

    /**
     * Conversation cleaner thread, that
     * clears unused conversations.
     *
     */
    private static class ConversationCleaner implements Runnable
    {
        private final WebBeansContext webBeansContext;

        private ConversationCleaner(WebBeansContext webBeansContext) {
            this.webBeansContext = webBeansContext;
        }

        public void run()
        {
            webBeansContext.getConversationManager().destroyWithRespectToTimout();

        }
    }

    protected void afterStopApplication(Object stopObject) throws Exception
    {

        //Clear the resource injection service
        ResourceInjectionService injectionServices = webBeansContext.getService(ResourceInjectionService.class);
        if(injectionServices != null)
        {
            injectionServices.clear();
        }

        //Comment out for commit OWB-502
        //ContextFactory.cleanUpContextFactory();

        WebBeansFinder.clearInstances(WebBeansUtil.getCurrentClassLoader());
    }

    /**
     * Returns servelt context otherwise throws exception.
     * @param object object
     * @return servlet context
     */
    private Object getServletContext(Object object) {
        if (object instanceof ServletContextEvent) {
            object = ((ServletContextEvent) object).getServletContext();
            return object;
        }
        return object;
    }

    protected void beforeStartApplication(Object startupObject)
    {
        //Do nothing as default
    }

    protected void beforeStopApplication(Object stopObject) throws Exception
    {
        if(service != null)
        {
            service.shutdownNow();
        }
    }
}
