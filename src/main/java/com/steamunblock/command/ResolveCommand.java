package com.steamunblock.command;

import com.steamunblock.dns.DohResolver;
import com.steamunblock.util.Log;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.util.List;
import java.util.concurrent.Callable;

@Command(name = "resolve", mixinStandardHelpOptions = true, description = "Resolve a domain via DNS-over-HTTPS (diagnostic tool).")
public class ResolveCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "Domain name to resolve")
    private String domain;

    @Option(names = {"--doh-url"}, defaultValue = "https://dns.google/resolve",
            description = "DoH endpoint (default: ${DEFAULT-VALUE})")
    private String dohUrl;

    @Override
    public Integer call() {
        Log.info("Resolving " + domain + " via " + dohUrl + " ...");

        DohResolver resolver = new DohResolver(dohUrl);
        List<String> ips = resolver.resolveAll(domain);

        if (ips.isEmpty()) {
            Log.error("Could not resolve " + domain);
            return 1;
        }

        Log.ok(domain + " -> " + String.join(", ", ips));
        return 0;
    }
}
