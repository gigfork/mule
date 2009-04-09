/*
 * $Id$
 * --------------------------------------------------------------------------------------
 * Copyright (c) MuleSource, Inc.  All rights reserved.  http://www.mulesource.com
 *
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */

package org.mule.config.spring.hotdeploy;

import org.mule.api.MuleContext;
import org.mule.api.config.ConfigurationException;
import org.mule.config.ConfigResource;
import org.mule.config.spring.SpringXmlConfigurationBuilder;
import org.mule.context.DefaultMuleContextFactory;
import org.mule.util.FileUtils;
import org.mule.util.SystemUtils;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Collection;
import java.util.Date;
import java.util.Timer;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 *
 */
public class ReloadableBuilder extends SpringXmlConfigurationBuilder
{

    protected static final ClassLoader rootClassloader = Thread.currentThread().getContextClassLoader();

    protected final transient Log logger = LogFactory.getLog(getClass());
    // TODO multiple resource monitoring
    protected File monitoredResource;

    private static final int RELOAD_INTERVAL_MS = 3000;

    public ReloadableBuilder(ConfigResource[] configResources)
    {
        super(configResources);
    }

    public ReloadableBuilder(String s) throws ConfigurationException
    {
        super(s);
    }


    public ReloadableBuilder(String[] strings) throws ConfigurationException
    {
        super(strings);
    }


    @Override
    public void configure(final MuleContext muleContext) throws ConfigurationException
    {
        try
        {
            // TODO dup
            final ConfigResource[] allResources;
            if (useDefaultConfigResource)
            {
                allResources = new ConfigResource[configResources.length + 1];
                allResources[0] = new ConfigResource(MULE_DEFAULTS_CONFIG);
                System.arraycopy(configResources, 0, allResources, 1, configResources.length);
            }
            else
            {
                allResources = configResources;
            }
            // end dup


            this.monitoredResource = new File(allResources[1].getUrl().getFile());

            URLClassLoader cl = createNewClassloader(rootClassloader);
            Thread.currentThread().setContextClassLoader(cl);



            if (logger.isInfoEnabled())
            {
                logger.info("Monitoring for hot-reload: " + monitoredResource);
            }

            FileWatcher watcher = new FileWatcher(monitoredResource)
            {
                private volatile boolean cancelled;

                protected synchronized void onChange(File file)
                {
                    if (!this.cancelled)
                    {
                        this.cancel();
                        this.cancelled = true;
                    }
                    else
                    {
                        // duplicate timer event
                        return;
                    }

                    if (logger.isInfoEnabled())
                    {
                        logger.info("================== Reloading " + file);
                    }


                    try
                    {
                        muleContext.dispose();
                        Thread.currentThread().setContextClassLoader(null);
                        URLClassLoader newCl = createNewClassloader(rootClassloader);
                        Thread.currentThread().setContextClassLoader(newCl);

                        //muleContext.initialise();
                        //muleContext.start();
                        DefaultMuleContextFactory f = new DefaultMuleContextFactory();
                        MuleContext newContext = f.createMuleContext(ReloadableBuilder.this);
                        doConfigure(newContext);
                        newContext.start();
                    }
                    catch (Exception ex)
                    {
                        throw new RuntimeException(ex);
                    }
                    //finally
                    //{
                    //    Thread.currentThread().setContextClassLoader(rootClassloader);
                    //}

                }
            };
            // need getUrl().getFile(), otherwise lastModified timestamp always returns 0
            Timer timer = new Timer();
            final int reloadIntervalMs = RELOAD_INTERVAL_MS;
            timer.schedule(watcher, new Date(), reloadIntervalMs);

            if (logger.isInfoEnabled())
            {
                logger.info("Reload interval: " + reloadIntervalMs);
            }

            super.configure(muleContext);
        }
        catch (IOException e)
        {
            throw new ConfigurationException(e);
        }
        //finally
        //{
        //    Thread.currentThread().setContextClassLoader(rootClassloader);
        //}

    }

    protected void doConfigure(final MuleContext muleContext) throws Exception
    {
        final ConfigResource[] allResources;
        if (useDefaultConfigResource)
        {
            allResources = new ConfigResource[configResources.length + 1];
            allResources[0] = new ConfigResource(MULE_DEFAULTS_CONFIG);
            System.arraycopy(configResources, 0, allResources, 1, configResources.length);
        }
        else
        {
            allResources = configResources;
        }

        createSpringRegistry(muleContext, createApplicationContext(muleContext, allResources));

    }

    protected URLClassLoader createNewClassloader(ClassLoader parentCl) throws IOException
    {
        // get lib dir on the same level as monitored resource and...
        File parentFile = this.monitoredResource.getParentFile();
        File libDir = new File(parentFile, "lib");

        if (logger.isInfoEnabled())
        {
            logger.info("Library directory: " + libDir);
        }

        Collection jars = FileUtils.listFiles(libDir, new String[] {"jar"}, false);

        File[] jarFiles = (File[]) jars.toArray(new File[jars.size()]);

        URL[] urls = FileUtils.toURLs(jarFiles);

        if (logger.isInfoEnabled())
        {
            StringBuilder sb = new StringBuilder();
            sb.append("Updating the following jars:").append(SystemUtils.LINE_SEPARATOR);
            sb.append("=============================").append(SystemUtils.LINE_SEPARATOR);
            for (URL url : urls)
            {
                sb.append(url).append(SystemUtils.LINE_SEPARATOR);
            }
            sb.append("=============================").append(SystemUtils.LINE_SEPARATOR);

            logger.info(sb.toString());
        }

        // grab all jars in there
        final URLClassLoader cl = new URLClassLoader(urls, parentCl);

        return cl;
    }

}