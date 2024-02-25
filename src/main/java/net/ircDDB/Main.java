package net.ircDDB;

import net.ircDDB.irc.IRCClient;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.Security;
import java.util.Properties;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

public class Main {
    private static final Logger LOGGER = LogManager.getLogger(Main.class);

    public static void main(String... args) {

        Security.setProperty("networkaddress.cache.ttl", "10");
        // we need DNS round robin, cache addresses only for 10 seconds

        String version = "";
        Package pkg;

        pkg = IRCDDBApp.class.getClassLoader().getDefinedPackage("net.ircDDB");


        if (pkg != null) {

            String v = pkg.getImplementationVersion();

            if (v != null) {
                version = "ircDDB:" + v;
            }
        }


        Properties properties = new Properties();

        try (var is = Files.newInputStream(Paths.get("ircDDB.properties"))) {
            properties.load(is);
        } catch (IOException e) {
            LOGGER.error("could not open file 'ircDDB.properties'", e);
            System.exit(1);
        }

        String irc_nick = properties.getProperty("irc_nick", "guest").trim().toLowerCase();
        String rptr_call = properties.getProperty("rptr_call", "nocall").trim().toLowerCase();

        boolean debug = properties.getProperty("debug", "0").equals("1");

        String irc_name;

        String[] n;
        if (rptr_call.equals("nocall")) {
            n = new String[1];
            n[0] = irc_nick;

            irc_name = irc_nick;
        } else {
            n = new String[4];

            n[0] = rptr_call + "-1";
            n[1] = rptr_call + "-2";
            n[2] = rptr_call + "-3";
            n[3] = rptr_call + "-4";

            irc_name = rptr_call;
        }


        int numTables = Integer.parseInt(properties.getProperty("ddb_num_tables", "2"));

        if ((numTables < 1) || (numTables > 10)) {
            LOGGER.error("invalid ddb_num_tables: " + numTables + " must be:  1 <= x <= 10");
            System.exit(1);
        }

        Pattern[] keyPattern = new Pattern[numTables];
        Pattern[] valuePattern = new Pattern[numTables];

        try {
            int i;

            for (i = 0; i < numTables; i++) {
                Pattern k = Pattern.compile(properties.getProperty("ddb_key_pattern" + i, "[A-Z0-9_]{8}"));
                Pattern v = Pattern.compile(properties.getProperty("ddb_value_pattern" + i, "[A-Z0-9_]{8}"));

                keyPattern[i] = k;
                valuePattern[i] = v;
            }

        } catch (PatternSyntaxException e) {
            LOGGER.error("pattern syntax error ", e);
            System.exit(1);
        }


        String extAppName = properties.getProperty("ext_app", "none");
        IRCDDBExtApp extApp = null;

        if (!extAppName.equals("none")) {
            try {
                Class<?> extAppClass = Class.forName(extAppName);

                extApp = (IRCDDBExtApp) extAppClass.getDeclaredConstructor().newInstance();

                if (!extApp.setParams(properties, numTables, keyPattern, valuePattern)) {
                    LOGGER.error("ext_app setParams failed - exit.");
                    System.exit(1);
                }

                Thread extappthr = new Thread(extApp);

                extappthr.start();

                pkg = extApp.getClass().getPackage();

                if (pkg != null) {

                    String v = pkg.getImplementationVersion();

                    if (v != null) {
                        String classname = extApp.getClass().getName();
                        int pos = classname.lastIndexOf('.');

                        if (pos < 0) {
                            pos = 0;
                        } else {
                            pos++;
                        }

                        if (!version.isEmpty()) {
                            version = version + " ";
                        }

                        version = version + classname.substring(pos) +
                                ":" + v;
                    }
                }


            } catch (Exception e) {
                LOGGER.error("external application: ", e);
                System.exit(1);
            }
        }

        String package_version = System.getenv("PACKAGE_VERSION");

        if (package_version != null) {
            if (!version.isEmpty()) {
                version = version + " ";
            }

            version = version + package_version;
        }


        String irc_channel = properties.getProperty("irc_channel", "#chat");
        String debug_channel = properties.getProperty("debug_channel", "none");

        if (irc_channel.equals(debug_channel)) {
            LOGGER.error("irc_channel and debug_channel must not have same value");
            System.exit(1);
        }

        if (debug_channel.equals("none")) {
            debug_channel = null;
        }

        LOGGER.info("Version " + version);

        IRCDDBApp app = new IRCDDBApp(numTables, keyPattern, valuePattern,
                irc_channel, debug_channel,
                extApp,
                properties.getProperty("dump_userdb_filename", "none"));

        app.setParams(properties);

        Thread appthr = new Thread(app);

        appthr.start();

        IRCClient irc = new IRCClient(app,
                properties.getProperty("irc_server_name", "localhost"),
                Integer.parseInt(properties.getProperty("irc_server_port", "9007")),
                irc_channel,
                debug_channel,
                irc_name,
                n,
                properties.getProperty("irc_password", "secret"),
                debug,
                version);


        Thread ircthr = new Thread(irc);

        ircthr.start();

    }
}
