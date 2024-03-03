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

        StringBuilder version = new StringBuilder();
        appendIRCDDBPackageVersion(version);
        Properties properties = new Properties();
        readPropertiesFile(properties);

        String irc_nick = properties.getProperty("irc_nick", "guest").trim().toLowerCase();
        String rptr_call = properties.getProperty("rptr_call", "nocall").trim().toLowerCase();

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
        verifyNumTables(numTables);

        Pattern[] keyPattern = new Pattern[numTables];
        Pattern[] valuePattern = new Pattern[numTables];
        createPatterns(numTables, properties, keyPattern, valuePattern);

        String extAppName = properties.getProperty("ext_app", "none");

        IRCDDBExtApp extApp = null;
        if (!extAppName.equals("none")) {
            extApp = startExternalApp(extAppName, properties, numTables, keyPattern, valuePattern, version);
        }

        appendPackageVersion(version);


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
                version.toString());

        Thread ircthr = new Thread(irc);
        ircthr.start();

    }

    private static void readPropertiesFile(Properties properties) {
        try (var is = Files.newInputStream(Paths.get("ircDDB.properties"))) {
            properties.load(is);
        } catch (IOException e) {
            LOGGER.error("could not open file 'ircDDB.properties'", e);
            System.exit(1);
        }
    }

    private static void appendIRCDDBPackageVersion(StringBuilder version) {
        Package pkg = IRCDDBApp.class.getPackage();
        if (pkg != null) {

            String v = pkg.getImplementationVersion();

            if (v != null) {
                version.append("ircDDB:").append(v);
            }
        }
    }

    private static void verifyNumTables(int numTables) {
        if ((numTables < 1) || (numTables > 10)) {
            LOGGER.error("invalid ddb_num_tables: " + numTables + " must be:  1 <= x <= 10");
            System.exit(1);
        }
    }

    private static void appendPackageVersion(StringBuilder version) {
        String package_version = System.getenv("PACKAGE_VERSION");

        if (package_version != null) {
            if (!version.isEmpty()) {
                version.append(" ");
            }

            version.append(package_version);
        }
    }

    private static void createPatterns(int numTables, Properties properties, Pattern[] keyPattern, Pattern[] valuePattern) {
        try {
            for (int i = 0; i < numTables; i++) {
                Pattern k = Pattern.compile(properties.getProperty("ddb_key_pattern" + i, "[A-Z0-9_]{8}"));
                Pattern v = Pattern.compile(properties.getProperty("ddb_value_pattern" + i, "[A-Z0-9_]{8}"));

                keyPattern[i] = k;
                valuePattern[i] = v;
            }

        } catch (PatternSyntaxException e) {
            LOGGER.error("pattern syntax error ", e);
            System.exit(1);
        }
    }

    private static IRCDDBExtApp startExternalApp(String extAppName, Properties properties, int numTables,
                                                 Pattern[] keyPattern, Pattern[] valuePattern,
                                                 StringBuilder version) {
        try {
            Class<?> extAppClass = Class.forName(extAppName);

            var extApp = (IRCDDBExtApp) extAppClass.getDeclaredConstructor().newInstance();

            if (!extApp.setParams(properties, numTables, keyPattern, valuePattern)) {
                LOGGER.error("ext_app setParams failed - exit.");
                System.exit(1);
            }

            Thread extappthr = new Thread(extApp);

            extappthr.start();

            var pkg = extApp.getClass().getPackage();

            if (pkg != null) {

                String v = pkg.getImplementationVersion();

                if (v != null) {
                    if (!version.isEmpty()) {
                        version.append(" ").append(extAppClass.getSimpleName()).append(":").append(v);
                    }
                }
            }
            return extApp;
        } catch (Exception e) {
            LOGGER.error("external application: ", e);
            System.exit(1);
            throw new IllegalStateException("unreachable - we system.exit before.", e);
        }
    }
}
