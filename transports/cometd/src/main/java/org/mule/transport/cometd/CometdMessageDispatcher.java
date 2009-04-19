/*
 * $Id$
 * --------------------------------------------------------------------------------------
 * Copyright (c) MuleSource, Inc.  All rights reserved.  http://www.mulesource.com
 *
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.transport.cometd;

import org.mule.transport.AbstractMessageDispatcher;
import org.mule.transport.cometd.container.CometdServletConnector;
import org.mule.api.MuleEvent;
import org.mule.api.MuleMessage;
import org.mule.api.lifecycle.InitialisationException;
import org.mule.api.endpoint.OutboundEndpoint;
import org.mule.util.MapUtils;

import java.util.Collection;
import java.util.List;
import java.util.ArrayList;
import java.util.Queue;

import org.mortbay.cometd.AbstractBayeux;
import org.apache.commons.collections.buffer.BoundedBuffer;
import org.apache.commons.collections.buffer.BoundedFifoBuffer;
import org.apache.commons.collections.Buffer;
import dojox.cometd.Client;

/**
 * Will dispatch Mule events to cometd clients available in Bayeux that are listening to this endpoint.
 */
public class CometdMessageDispatcher extends AbstractMessageDispatcher
{
    protected AbstractBayeux bayeux;

    protected boolean cacheMessages = false;

    protected int messageCacheSize = 500;

    protected Buffer messageCache;

    protected String channel;

    public CometdMessageDispatcher(OutboundEndpoint endpoint)
    {
        super(endpoint);
        cacheMessages = MapUtils.getBoolean(endpoint.getProperties(), "cacheMessages", false);
        messageCacheSize = MapUtils.getInteger(endpoint.getProperties(), "messageCacheSize", 500);
        channel = endpoint.getEndpointURI().getPath();
    }

    public AbstractBayeux getBayeux()
    {
        return bayeux;
    }

    public void setBayeux(AbstractBayeux bayeux)
    {

        this.bayeux = bayeux;
    }

    @Override
    protected void doInitialise() throws InitialisationException
    {
        if (cacheMessages)
        {
            messageCache = new BoundedFifoBuffer(messageCacheSize);
        }
    }

    protected void doDispatch(MuleEvent event) throws Exception
    {
        if (!connector.isStarted())
        {
            //TODO MULE-4320
            logger.warn("Servlet container has not yet initiailised, ignoring event: " + event.getMessage().getPayload());
            return;
        }


        Collection<Client> clients = bayeux.getClients();
        if (clients.size() == 0 && cacheMessages)
        {
            Object message = event.transformMessage();
            if (logger.isTraceEnabled())
            {
                logger.trace("There are no clients waiting, adding message to cache: " + message);
            }
            messageCache.add(message);
            return;
        }

        if (clients.size() > 0 && cacheMessages && !messageCache.isEmpty())
        {
            while (!messageCache.isEmpty())
            {
                for (Client client : clients)
                {
                    deliver(client, channel, messageCache.remove());
                }
            }
        }

        if (clients.size() > 0)
        {
            for (Client client : clients)
            {
                deliver(client, channel, event.transformMessage());
            }
        }
    }

    protected void deliver(Client client, String channel, Object data)
    {
        if (logger.isTraceEnabled())
        {
            logger.trace("Delivering to client id: " + client.getId() + " channel:" + channel);
        }
        client.deliver(client, channel, data, null);
    }

    protected MuleMessage doSend(MuleEvent event) throws Exception
    {
        doDispatch(event);
        return null;
    }

    @Override
    protected void doDispose()
    {
        if (messageCache != null)
        {
            messageCache.clear();
            messageCache = null;
        }
    }
}
