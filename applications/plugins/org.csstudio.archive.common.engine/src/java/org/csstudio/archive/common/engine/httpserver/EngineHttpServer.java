/*******************************************************************************
 * Copyright (c) 2010 Oak Ridge National Laboratory.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 ******************************************************************************/
package org.csstudio.archive.common.engine.httpserver;

import java.util.Dictionary;
import java.util.Hashtable;

import javax.annotation.Nonnull;
import javax.servlet.ServletException;

import org.csstudio.archive.common.engine.ArchiveEngineActivator;
import org.csstudio.archive.common.engine.model.EngineModel;
import org.eclipse.equinox.http.jetty.JettyConfigurator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.service.http.HttpContext;
import org.osgi.service.http.HttpService;
import org.osgi.service.http.NamespaceException;
import org.osgi.util.tracker.ServiceTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Web server for the engine.
 *  @author Kay Kasemir
 */
@SuppressWarnings("nls")
public class EngineHttpServer {
    private static final String EX_MSG = "Engine HTTP server could not be instantiated.";

    private static final Logger LOG =
        LoggerFactory.getLogger(EngineHttpServer.class);

    private String _pid;

    private ServiceTracker _httpTracker;

    /** Construct and start the server
     *  @param model Model to serve
     *  @param port TCP port
     * @throws EngineHttpServerException
     * @throws InvalidSyntaxException
     * @throws
     *  @throws Exception on error
     */
    public EngineHttpServer(@Nonnull final EngineModel model,
                            final int port) throws EngineHttpServerException {
        final BundleContext context =
            ArchiveEngineActivator.getDefault().getBundle().getBundleContext();
        HttpService httpService;
        try {
            httpService = createHttpService(context, port);

            final HttpContext httpContext = httpService.createDefaultHttpContext();
            httpService.registerResources("/", "/webroot", httpContext);

            httpService.registerServlet("/main", new MainResponse(model), null, httpContext);
            httpService.registerServlet("/groups", new GroupsResponse(model), null, httpContext);
            httpService.registerServlet("/disconnected", new DisconnectedResponse(model), null, httpContext);
            httpService.registerServlet("/group", new GroupResponse(model), null, httpContext);
            httpService.registerServlet("/channel", new ChannelResponse(model), null, httpContext);
            httpService.registerServlet("/channels", new ChannelListResponse(model), null, httpContext);
            httpService.registerServlet("/environment", new EnvironmentResponse(model), null, httpContext);
            httpService.registerServlet("/restart", new RestartResponse(model), null, httpContext);
            httpService.registerServlet("/reset", new ResetResponse(model), null, httpContext);
            httpService.registerServlet("/stop", new StopResponse(model), null, httpContext);
            httpService.registerServlet("/debug", new DebugResponse(model), null, httpContext);

        } catch (final InvalidSyntaxException e) {
            throw new EngineHttpServerException(EX_MSG, e);
        } catch (final ServletException e) {
            throw new EngineHttpServerException(EX_MSG, e);
        } catch (final NamespaceException e) {
            throw new EngineHttpServerException(EX_MSG, e);
        } catch (final Exception e) {
            throw new EngineHttpServerException(EX_MSG, e);
        }
        LOG.info("Engine HTTP Server port " + port);
    }

    /** Stop the server */
    public void stop() {
        try {
            stopHttpService(_pid);
        } catch (final Exception ex) {
            LOG.warn("Unknown exception while stopping Http Server", ex);
        }
    }

    @Nonnull
    private HttpService createHttpService(@Nonnull final BundleContext context,
                                          final int port) throws Exception {
        _pid = "HTTPD" + port;

        // Create a custom HttpService
        // avoid the auto-started instance
        final Dictionary<String, Object> dictionary = new Hashtable<String, Object>();
        dictionary.put("http.port", new Integer(port));
        dictionary.put("other.info", _pid);

        JettyConfigurator.startServer(_pid, dictionary);

        // Locate that custom server so we can add servlets
        // (thanks to Gunnar Wagenknecht for this info)
        // Tried to use the service.pid instead of other.info,
        // but that didn't seem to work.
        final String filter =
            String.format("(&(objectClass=%s)(other.info=%s))",
                            HttpService.class.getName(), _pid);
        _httpTracker =
            new ServiceTracker(context, context.createFilter(filter), null);
        _httpTracker.open();

        final Object[] services = _httpTracker.getServices();
        if (services == null) {
            throw new Exception("No HttpService found");
        }
        if (services.length != 1) {
            throw new Exception("Found " + services.length + " HttpServices instead of one");
        }
        if (!(services[0] instanceof HttpService)) {
            throw new Exception("Got " + services[0].getClass().getName() + " instead of HttpService");
        }

        return (HttpService) services[0];
    }

    /**
     * Stop a HttpService that was started at given port.
     *  <p>
     *  Will only work with HttpServices that were started by
     *  <code>createHttpService</code>
     *  @port Port where the HttpService was started
     *  @throws Exception on error
     *  @see #createHttpService
     */
    private void stopHttpService(@Nonnull final String pid) throws Exception {
        if (pid != null) {
            JettyConfigurator.stopServer(pid);
        }
        if(_httpTracker != null) {
            _httpTracker.close();
        }
    }
}