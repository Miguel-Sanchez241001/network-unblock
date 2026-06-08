package com.steamunblock.command;

import com.steamunblock.hosts.HostsFileEditor;
import com.steamunblock.util.Log;
import com.steamunblock.util.Platform;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

@Command(name = "hosts", mixinStandardHelpOptions = true, description = "Manage system hosts file for Steam domains.",
         subcommands = {
             HostsCommand.AddCommand.class,
             HostsCommand.RemoveCommand.class,
             HostsCommand.ShowCommand.class
         })
public class HostsCommand implements Runnable {

    @Override
    public void run() {
        picocli.CommandLine.usage(this, System.out);
    }

    @Command(name = "add", description = "Add Steam domain entries to hosts file (requires admin/root).")
    static class AddCommand implements Callable<Integer> {

        @Option(names = {"--lancache-fix"},
                description = "Include LANCache fix entries for Lima region servers")
        private boolean lancacheFix;

        @Override
        public Integer call() {
            Map<String, String> entries = new LinkedHashMap<>();

            if (lancacheFix) {
                entries.put("lancache.steamcontent.com", "155.133.244.4");
                entries.put("cache1-lim1.steamcontent.com", "155.133.244.4");
                entries.put("cache2-lim1.steamcontent.com", "155.133.244.20");
                entries.put("cache3-lim1.steamcontent.com", "155.133.244.3");
                entries.put("cache4-lim1.steamcontent.com", "155.133.244.19");
                entries.put("cache5-lim1.steamcontent.com", "155.133.244.4");
                entries.put("cache6-lim1.steamcontent.com", "155.133.244.20");
                entries.put("cache7-lim1.steamcontent.com", "155.133.244.3");
            }

            entries.put("store.steampowered.com", "23.57.121.205");
            entries.put("api.steampowered.com", "96.6.206.56");
            entries.put("steamcommunity.com", "96.6.206.56");
            entries.put("login.steampowered.com", "96.6.206.56");
            entries.put("help.steampowered.com", "96.6.206.56");

            try {
                HostsFileEditor editor = new HostsFileEditor();
                editor.addEntries(entries);
                Log.ok("Added " + entries.size() + " entries to " + Platform.getHostsFilePath());
                Platform.flushDnsCache();
                Log.ok("DNS cache flushed");
                return 0;
            } catch (java.nio.file.AccessDeniedException e) {
                Log.error("Permission denied. Run with admin/root privileges.");
                return 1;
            } catch (Exception e) {
                Log.error("Failed to modify hosts file: " + e.getMessage());
                return 1;
            }
        }
    }

    @Command(name = "remove", description = "Remove Steam domain entries from hosts file (requires admin/root).")
    static class RemoveCommand implements Callable<Integer> {
        @Override
        public Integer call() {
            try {
                HostsFileEditor editor = new HostsFileEditor();
                int removed = editor.removeEntries();
                if (removed > 0) {
                    Log.ok("Removed " + removed + " entries from " + Platform.getHostsFilePath());
                    Platform.flushDnsCache();
                    Log.ok("DNS cache flushed");
                } else {
                    Log.info("No steam-unblock entries found in hosts file");
                }
                return 0;
            } catch (java.nio.file.AccessDeniedException e) {
                Log.error("Permission denied. Run with admin/root privileges.");
                return 1;
            } catch (Exception e) {
                Log.error("Failed to modify hosts file: " + e.getMessage());
                return 1;
            }
        }
    }

    @Command(name = "show", description = "Show current steam-unblock entries in hosts file.")
    static class ShowCommand implements Callable<Integer> {
        @Override
        public Integer call() {
            try {
                HostsFileEditor editor = new HostsFileEditor();
                List<String> entries = editor.showEntries();
                if (entries.isEmpty()) {
                    Log.info("No steam-unblock entries found in hosts file");
                } else {
                    Log.info("Current steam-unblock entries in " + Platform.getHostsFilePath() + ":");
                    entries.forEach(e -> System.out.println("  " + e));
                }
                return 0;
            } catch (Exception e) {
                Log.error("Failed to read hosts file: " + e.getMessage());
                return 1;
            }
        }
    }
}
