package com.steamunblock;

import com.steamunblock.command.HostsCommand;
import com.steamunblock.command.ProxyCommand;
import com.steamunblock.command.ResolveCommand;
import picocli.CommandLine;

@CommandLine.Command(
    name = "steam-unblock",
    mixinStandardHelpOptions = true,
    version = "steam-unblock 1.0.0",
    description = "Bypass DNS hijacking and DPI/SNI blocking of Steam on restricted networks.",
    subcommands = {
        ProxyCommand.class,
        HostsCommand.class,
        ResolveCommand.class
    }
)
public class SteamUnblock implements Runnable {

    public static void main(String[] args) {
        int exitCode = new CommandLine(new SteamUnblock()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public void run() {
        CommandLine.usage(this, System.out);
    }
}
