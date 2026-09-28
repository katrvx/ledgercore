package com.ledgercore.http;

import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.util.thread.ThreadPool;
import spark.embeddedserver.jetty.JettyServerFactory;

// spark creates the jetty connector itself, so the version is turned off right before jetty starts
public class JettyWithoutVersion implements JettyServerFactory {

    // same defaults as spark's own factory
    @Override
    public Server create(int maxThreads, int minThreads, int threadTimeoutMillis) {
        if (maxThreads <= 0) {
            return create(new QueuedThreadPool());
        }
        int min = minThreads > 0 ? minThreads : 8;
        int idleTimeout = threadTimeoutMillis > 0 ? threadTimeoutMillis : 60_000;
        return create(new QueuedThreadPool(maxThreads, min, idleTimeout));
    }

    @Override
    public Server create(ThreadPool threadPool) {
        Server server = new Server(threadPool);
        server.addLifeCycleListener(new LifeCycle.Listener() {
            @Override
            public void lifeCycleStarting(LifeCycle event) {
                hideVersion(server);
            }
        });
        return server;
    }

    private void hideVersion(Server server) {
        for (Connector connector : server.getConnectors()) {
            HttpConnectionFactory http = connector.getConnectionFactory(HttpConnectionFactory.class);
            http.getHttpConfiguration().setSendServerVersion(false);
        }
    }
}
