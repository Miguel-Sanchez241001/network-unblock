package com.steamunblock.command;

import com.steamunblock.dns.DohResolver;
import com.steamunblock.dns.SteamDomains;
import com.steamunblock.proxy.ProxyServer;
import com.steamunblock.tls.TlsRecordSplitter;
import com.steamunblock.util.Log;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.util.concurrent.Callable;

@Command(name = "proxy", mixinStandardHelpOptions = true, description = "Start the HTTP/CONNECT proxy server with DNS bypass and TLS record splitting.")
public class ProxyCommand implements Callable<Integer> {

    @Option(names = {"-p", "--port"}, defaultValue = "8888",
            description = "Proxy listen port (default: ${DEFAULT-VALUE})")
    private int port;

    @Option(names = {"-b", "--bind"}, defaultValue = "127.0.0.1",
            description = "Bind address (default: ${DEFAULT-VALUE})")
    private String bindAddress;

    @Option(names = {"--doh-url"}, defaultValue = "https://dns.google/resolve",
            description = "DNS-over-HTTPS endpoint (default: ${DEFAULT-VALUE})")
    private String dohUrl;

    @Option(names = {"--split-delay"}, defaultValue = "5",
            description = "Delay in ms between TLS record segments (default: ${DEFAULT-VALUE})")
    private int splitDelayMs;

    @Option(names = {"--no-split"},
            description = "Disable TLS record splitting (DNS bypass only)")
    private boolean noSplit;

    @Option(names = {"--domains-file"},
            description = "Path to custom domains JSON file")
    private Path domainsFile;

    @Option(names = {"-v", "--verbose"},
            description = "Enable verbose logging")
    private boolean verbose;

    @Override
    public Integer call() throws Exception {
        SteamDomains domains = (domainsFile != null)
                ? SteamDomains.loadFrom(domainsFile)
                : SteamDomains.loadDefault();

        DohResolver dohResolver = new DohResolver(dohUrl);
        TlsRecordSplitter splitter = noSplit ? null : new TlsRecordSplitter(splitDelayMs);

        Log.banner();
        Log.info("Proxy: http://" + bindAddress + ":" + port);
        Log.info("TLS Record Splitting: " + (noSplit ? "DISABLED" : "ENABLED (delay=" + splitDelayMs + "ms)"));
        Log.info("DoH endpoint: " + dohUrl);
        System.out.println();

        Log.info("Mapped domains:");
        Log.separator();
        domains.getAllEntries().forEach((domain, ips) ->
            System.out.printf("  %-45s -> %s%n", domain, String.join(", ", ips))
        );
        Log.separator();
        System.out.println();

        ProxyServer server = new ProxyServer(port, bindAddress, domains, dohResolver, splitter, verbose);

        Runtime.getRuntime().addShutdownHook(Thread.ofVirtual().unstarted(() -> {
            Log.warn("Shutting down proxy...");
            server.stop();
        }));

        server.start();
        Log.info("Waiting for connections...");
        System.out.println();

        // Block main thread until interrupted
        Thread.currentThread().join();
        return 0;
    }
}
