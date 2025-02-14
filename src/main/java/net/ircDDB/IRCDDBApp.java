/*

ircDDB

Copyright (C) 2011   Michael Dirska, DL1BFF (dl1bff@mdx.de)
Copyright (C) 2024   Fabian Franz BSc., OE9LTX

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 2 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program.  If not, see <http://www.gnu.org/licenses/>.

*/


package net.ircDDB;

import net.ircDDB.irc.IRCMessage;
import net.ircDDB.irc.IRCMessageQueue;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import static java.nio.charset.StandardCharsets.UTF_8;


public class IRCDDBApp implements IRCApplication, Runnable {
    private static final Logger LOGGER = LogManager.getLogger(IRCDDBApp.class);

    private final IRCDDBExtApp extApp;

    private IRCMessageQueue sendQ;
    private final Map<String, UserObject> user = new ConcurrentHashMap<>();
    private String currentServer;

    private String myNick;

    int state;
    int timer;

    private final Pattern datePattern;
    private final Pattern timePattern;
    private final Pattern[] keyPattern;
    private final Pattern[] valuePattern;
    private final Pattern tablePattern;
    private final Pattern hexcharPattern;
    private final Pattern defaultValuePattern;

    private final SimpleDateFormat parseDateFormat;

    private final String updateChannel;
    private final String debugChannel;

    private boolean acceptPublicUpdates;
    private final IRCMessageQueue[] publicUpdates;

    private final String dumpUserDBFileName;

    private final int numberOfTables;
    private int numberOfTablesToSync;

    private Properties properties;

    private final Instant startupTime;
    private String reconnectReason;

    private int channelTimeout;


    private String rptrLocation;
    private String[] rptrFrequencies;
    private int numRptrFreq;
    private String rptrInfoURL;

    IRCDDBApp(int numTables, Pattern[] k, Pattern[] v, String u_chan, String dbg_chan,
              IRCDDBExtApp ea, String dumpFileName) {
        extApp = ea;

        sendQ = null;
        currentServer = null;
        acceptPublicUpdates = false;

        numberOfTables = numTables;
        numberOfTablesToSync = numTables;

        publicUpdates = new IRCMessageQueue[numberOfTables];

        for (int i = 0; i < numberOfTables; i++) {
            publicUpdates[i] = new IRCMessageQueue();
        }


        userListReset();

        state = 0;
        timer = 0;
        myNick = "none";

        parseDateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        parseDateFormat.setTimeZone(TimeZone.getTimeZone("GMT"));

        datePattern = Pattern.compile("20[0-9][0-9]-((1[0-2])|(0[1-9]))-((3[01])|([12][0-9])|(0[1-9]))");
        timePattern = Pattern.compile("((2[0-3])|([01][0-9])):[0-5][0-9]:[0-5][0-9]");
        tablePattern = Pattern.compile("[0-9]");
        hexcharPattern = Pattern.compile("[0-9A-F]");
        defaultValuePattern = Pattern.compile("[A-Z0-9_]{8}");

        keyPattern = k;
        valuePattern = v;

        updateChannel = u_chan;
        debugChannel = dbg_chan;

        dumpUserDBFileName = dumpFileName;

        startupTime = Instant.now();
        reconnectReason = "startup";

        channelTimeout = 0;

        rptrLocation = null;
        rptrFrequencies = null;
        numRptrFreq = 0;
        rptrInfoURL = null;
    }

    void setParams(Properties p) {
        properties = p;

        numberOfTablesToSync = Integer.parseInt(properties.getProperty("ddb_num_tables_sync", "2"));

        rptrInfoURL = properties.getProperty("rptr_info_url", "").trim().replaceAll("[^\\p{Graph}]", "");

        try {

            double latitude = Double.parseDouble(properties.getProperty("rptr_pos_latitude", "0"));
            double longitude = Double.parseDouble(properties.getProperty("rptr_pos_longitude", "0"));

            if ((latitude != 0.0) || (longitude != 0.0)) {
                StringBuilder desc1 = new StringBuilder(properties.getProperty("rptr_pos_text1", "")
                    .trim().replaceAll("[^a-zA-Z0-9 +&(),./'-]", ""));
                StringBuilder desc2 = new StringBuilder(properties.getProperty("rptr_pos_text2", "")
                    .trim().replaceAll("[^a-zA-Z0-9 +&(),./'-]", ""));
                String rangeUnit = properties.getProperty("rptr_range_unit", "mile").trim().toLowerCase();
                String aglUnit = properties.getProperty("rptr_agl_unit", "meter").trim().toLowerCase();


                while (desc1.length() < 20) {
                    desc1.append(" ");
                }

                while (desc2.length() < 20) {
                    desc2.append(" ");
                }

                rptrLocation = String.format("%1$+09.5f %2$+010.5f", latitude, longitude).replace(',', '.') +
                        " " + desc1.substring(0, 20).replace(' ', '_') +
                        " " + desc2.substring(0, 20).replace(' ', '_');

                String[] modules = {"A", "B", "C", "D", "AD", "BD", "CD", "DD"};

                numRptrFreq = modules.length;
                rptrFrequencies = new String[numRptrFreq];

                for (int i = 0; i < numRptrFreq; i++) {
                    double freq = Double.parseDouble(properties.getProperty("rptr_freq_" + modules[i], "0"));
                    double shift = Double.parseDouble(properties.getProperty("rptr_duplex_shift_" + modules[i], "0"));
                    double range = Double.parseDouble(properties.getProperty("rptr_range_" + modules[i], "0"));
                    double agl = Double.parseDouble(properties.getProperty("rptr_agl_" + modules[i], "0"));

                    if ((freq > 1.0) && (range > 0.0)) {
                        if (rangeUnit.equals("meter") || rangeUnit.equals("meters")) {
                            range /= 1609.344;
                        } else if (rangeUnit.equals("km") || rangeUnit.equals("kilometer")) {
                            range /= 1.609344;
                        }

                        if (aglUnit.equals("feet") || aglUnit.equals("foot")) {
                            agl *= 0.3048;
                        }

                        rptrFrequencies[i] = String.format("%1$s %2$011.5f %3$+010.5f %4$06.2f %5$06.1f",
                                modules[i], freq, shift, range, agl).replace(',', '.');
                    } else {
                        rptrFrequencies[i] = null;
                    }
                }
            }
        } catch (NumberFormatException e) {
            rptrFrequencies = null;
            numRptrFreq = 0;
            LOGGER.error("NumberFormatException in rptr QTH/QRG");
        }
    }

    public void userJoin(String nick, String name, String host) {
        // System.out.println("APP: join " + nick + " " + name + " " + host);
        UserObject u = new UserObject(nick, name, host);

        user.put(nick, u);

        if (extApp != null) {
            extApp.userJoin(nick, name, host);

            if (debugChannel != null) {
                IRCMessage m2 = new IRCMessage();
                m2.command = "PRIVMSG";
                m2.numParams = 2;
                m2.params[0] = debugChannel;
                m2.params[1] = nick + ": LOGIN: " + host + " " + name;

                IRCMessageQueue q = getSendQ();
                if (q != null) {
                    q.putMessage(m2);
                }
            }
        }

    }

    public void userLeave(String nick) {
        if (extApp != null) {
            if (user.containsKey(nick)) {
                extApp.userLeave(nick);

                if (debugChannel != null) {
                    IRCMessage m2 = new IRCMessage();
                    m2.command = "PRIVMSG";
                    m2.numParams = 2;
                    m2.params[0] = debugChannel;
                    m2.params[1] = nick + ": LOGOUT";

                    IRCMessageQueue q = getSendQ();
                    if (q != null) {
                        q.putMessage(m2);
                    }
                }
            }
        }

        user.remove(nick);

        if (currentServer != null) {
            UserObject me = user.get(myNick);

            if ((me == null) || (!me.isOp())) {
                // if I am not op, then look for new server

                if (currentServer.equals(nick)) {
                    // currentServer = null;
                    state = 2;  // choose new server
                    timer = 200;
                    acceptPublicUpdates = false;
                    reconnectReason = nick + " left channel";
                }
            }
        }

    }

    public void userListReset() {
        user.clear();

        if (extApp != null) {
            extApp.userListReset();
        }
    }

    public void setCurrentNick(String nick) {
        myNick = nick;

        if (extApp != null) {
            extApp.setCurrentNick(nick);
        }
    }

    public void setTopic(String topic) {
        if (extApp != null) {
            extApp.setTopic(topic);
        }
    }


    boolean findServerUser() {
        boolean found = false;

        synchronized (user) {
            Collection<UserObject> v = user.values();

            for (UserObject u : v) {
                if (u.getNick().startsWith("s-") && u.isOp() && !myNick.equals(u.getNick())) {
                    currentServer = u.getNick();
                    found = true;
                    if (extApp != null) {
                        extApp.setCurrentServerNick(currentServer);
                    }
                    break;
                }
            }
        }

        return found;
    }


    public void userChanOp(String nick, boolean op) {
        UserObject u = user.get(nick);

        if (u != null) {
            if ((extApp != null) && (u.isOp() != op)) {
                extApp.userChanOp(nick, op);
            }
            u.setOp(op);
        }
    }


    IRCDDBExtApp.UpdateResult processUpdate(int tableID, Scanner s, String ircUser, String msg) {
        if (s.hasNext(datePattern)) {
            String d = s.next(datePattern);

            if (s.hasNext(timePattern)) {
                String t = s.next(timePattern);


                Instant dbDate;

                try {
                    dbDate = parseDateFormat.parse(d + " " + t).toInstant();
                } catch (ParseException e) {
                    dbDate = null;
                }

                if ((dbDate != null) && s.hasNext(keyPattern[tableID])) {
                    String key = s.next(keyPattern[tableID]);


                    if (s.hasNext(valuePattern[tableID])) {
                        String value = s.next(valuePattern[tableID]);

                        if (extApp != null) {
                            return extApp.dbUpdate(tableID, dbDate, key, value, ircUser, msg);
                        }
                    }
                }
            }
        }

        return null;
    }

    void enablePublicUpdates() {
        acceptPublicUpdates = true;

        for (int i = (numberOfTables - 1); i >= 0; i--) {
            while (publicUpdates[i].messageAvailable()) {
                IRCMessage m = publicUpdates[i].getMessage();

                String msg = m.params[1];

                Scanner s = new Scanner(msg);

                processUpdate(i, s, null, null);
            }
        }
    }

    public void msgChannel(IRCMessage m) {
        if (m.getPrefixNick().startsWith("s-"))  // server msg
        {
            int tableID = 0;

            String msg = m.params[1];

            Scanner s = new Scanner(msg);

            if (s.hasNext(tablePattern)) {
                tableID = s.nextInt();
                if ((tableID < 0) || (tableID >= numberOfTables)) {
                    LOGGER.debug("invalid table ID " + tableID);
                    return;
                }
            }

            if (s.hasNext(datePattern)) {
                if (acceptPublicUpdates) {
                    processUpdate(tableID, s, null, null);
                } else {
                    publicUpdates[tableID].putMessage(m);
                }
            } else {
                if (msg.startsWith("IRCDDB ")) {
                    channelTimeout = 0;
                } else if (extApp != null) {
                    extApp.msgChannel(m);
                }
            }
        }
    }

    private String getTableIDString(int tableID, boolean spaceBeforeNumber) {
        if (tableID == 0) {
            return "";
        } else if ((tableID > 0) && (tableID < numberOfTables)) {
            if (spaceBeforeNumber) {
                return " " + tableID;
            } else {
                return tableID + " ";
            }
        } else {
            return " TABLE_ID_OUT_OF_RANGE ";
        }
    }


    String checkPrivCommand(String msg) {
        Scanner s = new Scanner(msg);

        String command = s.next();

        int tableID = 0;

        if (s.hasNext(tablePattern)) {
            tableID = s.nextInt();
        }

        String d = s.next(datePattern);
        String t = s.next(timePattern);

        String key = s.next(keyPattern[tableID]);
        String value = s.next(valuePattern[tableID]);

        String dummy;

        if (s.hasNext(hexcharPattern)) {
            dummy = s.next(hexcharPattern);
        }

        if (s.hasNext(defaultValuePattern))  // rpt2
        {
            dummy = s.next(defaultValuePattern);
        } else {
            return null;
        }

        if (s.hasNext(defaultValuePattern))  // urcall
        {
            String urcall = s.next(defaultValuePattern);

            if (urcall.startsWith("PRIV")) {
                return urcall;
            }

            if (urcall.startsWith("VIS")) {
                return urcall;
            }

            if (urcall.startsWith("//")) {
                return urcall;
            }
        }

        return null;
    }

    public void msgQuery(IRCMessage m) {

        String msg = m.params[1];

        Scanner s = new Scanner(msg);

        String command;

        if (s.hasNext()) {
            command = s.next();
        } else {
            return; // no command
        }

        int tableID = 0;

        if (s.hasNext(tablePattern)) {
            tableID = s.nextInt();
            if ((tableID < 0) || (tableID >= numberOfTables)) {
                LOGGER.debug("invalid table ID " + tableID);
                return;
            }
        }

        switch (command) {
            case "UPDATE" -> handleUpdate(m, s, tableID, msg);
            case "SENDLIST" -> handleSendList(m, s, tableID);
            case "LIST_END" -> {
                if (state == 5) // if in sendlist processing state
                {
                    state = 3;  // get next table
                }
            }
            case "LIST_MORE" -> {
                if (state == 5) // if in sendlist processing state
                {
                    state = 4;  // send next SENDLIST
                }
            }
            case "OP_BEG" -> handleOpBeg(m);
            case "QUIT_NOW" -> handleQuitNow(m);
            case "SHOW_PROPERTIES" -> handleShowProperties(m);
            case "IRCDDB" -> {
                if (debugChannel != null) {
                    IRCMessage m2 = new IRCMessage();
                    m2.command = "PRIVMSG";
                    m2.numParams = 2;
                    m2.params[0] = debugChannel;
                    m2.params[1] = m.getPrefixNick() + ": " + msg;

                    IRCMessageQueue q = getSendQ();
                    if (q != null) {
                        q.putMessage(m2);
                    }
                }
            }
            default -> {
                if (extApp != null) {
                    extApp.msgQuery(m);
                }
            }
        }
    }

    private void handleOpBeg(IRCMessage m) {
        UserObject me = user.get(myNick);
        UserObject other = user.get(m.getPrefixNick()); // nick of other user


        if ((me != null) && (other != null) && me.isOp() && !other.isOp()
                && other.getNick().startsWith("s-") && me.getNick().startsWith("s-")) {
            IRCMessage m2 = new IRCMessage();
            m2.command = "MODE";
            m2.numParams = 3;
            m2.params[0] = updateChannel;
            m2.params[1] = "+o";
            m2.params[2] = other.getNick();

            IRCMessageQueue q = getSendQ();
            if (q != null) {
                q.putMessage(m2);
            }
        }
    }

    private void handleShowProperties(IRCMessage m) {
        UserObject other = user.get(m.getPrefixNick()); // nick of other user


        if ((other != null) && other.isOp()
                && other.getNick().startsWith("u-")) {
            int num = properties.size();

            for (Enumeration<Object> e = properties.keys(); e.hasMoreElements(); ) {
                String k = (String) e.nextElement();
                String v = properties.getProperty(k);

                if (k.equals("irc_password")) {
                    v = "*****";
                } else {
                    v = "(" + v + ")";
                }

                IRCMessage m2 = new IRCMessage();
                m2.command = "PRIVMSG";
                m2.numParams = 2;
                m2.params[0] = m.getPrefixNick();
                m2.params[1] = num + ": (" + k + ") " + v;

                IRCMessageQueue q = getSendQ();
                if (q != null) {
                    q.putMessage(m2);
                }
                num--;
            }
        }
    }

    private void handleQuitNow(IRCMessage m) {
        UserObject other = user.get(m.getPrefixNick()); // nick of other user


        if ((other != null) && other.isOp()
                && other.getNick().startsWith("u-")) {

            IRCMessage m2 = new IRCMessage();
            m2.command = "QUIT";
            m2.numParams = 1;
            m2.params[0] = "QUIT_NOW sent by " + other.getNick();

            IRCMessageQueue q = getSendQ();
            if (q != null) {
                q.putMessage(m2);
            }

            timer = 3;
            state = 11;  // exit
            reconnectReason = "QUIT_NOW received";
        }
    }

    private void handleUpdate(IRCMessage m, Scanner s, int tableID, String msg) {
        UserObject other = user.get(m.getPrefixNick()); // nick of other user


        if (s.hasNext(datePattern) &&
                (other != null)) {
            IRCDDBExtApp.UpdateResult result = processUpdate(tableID, s, other.getNick(), msg);

            if (result != null) {
                boolean sendUpdate = shouldUpdateBeSent(result);

                UserObject me = user.get(myNick);

                if ((me != null) && me.isOp() && sendUpdate)  // send only if i am operator
                {

                    IRCMessage m2 = new IRCMessage();
                    m2.command = "PRIVMSG";
                    m2.numParams = 2;
                    m2.params[0] = updateChannel;
                    m2.params[1] = getTableIDString(tableID, false) +
                            parseDateFormat.format(Date.from(result.getNewObj().getModTime())) + " " +
                            result.getNewObj().getKey() + " " + result.getNewObj().getValue() + "  (from: " + m.getPrefixNick() + ")";

                    IRCMessageQueue q = getSendQ();
                    if (q != null) {
                        q.putMessage(m2);
                    }
                }

                String privCommand = null;
                boolean isSTNCall = false;

                if (tableID == 0) {
                    isSTNCall = result.getNewObj().getKey().startsWith("STN");
                    privCommand = checkPrivCommand(msg);
                }

                if (privCommand != null) {
                    String setPriv = getStringIRC(privCommand, result);

                    if ((setPriv != null) && (me != null) && me.isOp()
                            && (numberOfTables >= 3))  // send only if i am operator and ddb_num_tables >= 3
                    {
                        IRCMessage m2 = new IRCMessage();
                        m2.command = "PRIVMSG";
                        m2.numParams = 2;
                        m2.params[0] = updateChannel;
                        m2.params[1] = getTableIDString(2, false) +
                                parseDateFormat.format(result.getNewObj().getModTime()) + " " +
                                result.getNewObj().getKey() + " " + setPriv + "  (from: " + m.getPrefixNick() + ")";

                        IRCMessageQueue q = getSendQ();
                        if (q != null) {
                            q.putMessage(m2);
                        }

                        if (extApp != null) {
                            extApp.dbUpdate(2, result.getNewObj().getModTime(), result.getNewObj().getKey(), setPriv, myNick, null);
                        }

                    }
                }

                if ((debugChannel != null) && (result.getModifiedLogLine() != null)
                        && (privCommand == null) && (!isSTNCall)) {
                    IRCMessage m2 = new IRCMessage();
                    m2.command = "PRIVMSG";
                    m2.numParams = 2;
                    m2.params[0] = debugChannel;
                    m2.params[1] = m.getPrefixNick() + ": UPDATE OK: " + result.getModifiedLogLine();

                    IRCMessageQueue q = getSendQ();
                    if (q != null) {
                        q.putMessage(m2);
                    }
                }
            } else {
                if (debugChannel != null) {
                    IRCMessage m2 = new IRCMessage();
                    m2.command = "PRIVMSG";
                    m2.numParams = 2;
                    m2.params[0] = debugChannel;
                    m2.params[1] = m.getPrefixNick() + ": UPDATE ERROR: " + msg;

                    IRCMessageQueue q = getSendQ();
                    if (q != null) {
                        q.putMessage(m2);
                    }
                }

            }
        }
    }

    private static String getStringIRC(String privCommand, IRCDDBExtApp.UpdateResult result) {
        String setPriv = null;

        if ((privCommand.equals("PRIV_ON_") || privCommand.equals("PRIV__ON")) && (!result.isHideFromLog())) {
            setPriv = "P_______";
        }

        if (privCommand.equals("PRIV_OFF") && (result.isHideFromLog())) {
            setPriv = "X_______";
        }

        if ((privCommand.equals("VIS_OFF_") || privCommand.equals("VIS__OFF"))
                && (!result.isHideFromLog())) {
            setPriv = "P_______";
        }

        if ((privCommand.equals("VIS_ON__") || privCommand.equals("VIS__ON_") || privCommand.equals("VIS___ON"))
                && (result.isHideFromLog())) {
            setPriv = "X_______";
        }
        return setPriv;
    }

    private void handleSendList(IRCMessage m, Scanner s, int tableID) {
        String answer = "LIST_END";

        if (s.hasNext(datePattern)) {
            String d = s.next(datePattern);

            if (s.hasNext(timePattern)) {
                String t = s.next(timePattern);


                Instant dbDate;

                try {
                    dbDate = parseDateFormat.parse(d + " " + t).toInstant();
                } catch (ParseException e) {
                    dbDate = null;
                }

                if ((dbDate != null) && (extApp != null)) {
                    final int NUM_ENTRIES = 30;

                    var l = extApp.getDatabaseObjects(tableID, dbDate, NUM_ENTRIES);

                    int count = 0;

                    if (l != null) {
                        for (IRCDDBExtApp.DatabaseObject o : l) {
                            IRCMessage m3 = new IRCMessage(
                                    m.getPrefixNick(),
                                    "UPDATE" + getTableIDString(tableID, true) +
                                            " " + parseDateFormat.format(o.getModTime()) + " "
                                            + o.getKey() + " " + o.getValue());

                            IRCMessageQueue q = getSendQ();
                            if (q != null) {
                                q.putMessage(m3);
                            }

                            count++;
                        }
                    }

                    if (count > NUM_ENTRIES) {
                        answer = "LIST_MORE";
                    }
                }
            }
        }

        IRCMessage m2 = new IRCMessage();
        m2.command = "PRIVMSG";
        m2.numParams = 2;
        m2.params[0] = m.getPrefixNick();
        m2.params[1] = answer;

        IRCMessageQueue q = getSendQ();
        if (q != null) {
            q.putMessage(m2);
        }
    }

    private boolean shouldUpdateBeSent(IRCDDBExtApp.UpdateResult result) {
        boolean sendUpdate = false;

        if (result.isKeyWasNew()) {
            sendUpdate = true;
        } else {

            if (result.getNewObj().getValue().equals(result.getOldObj().getValue()))
            {
                long newMillis = result.getNewObj().getModTime().getNano() / 1000;
                long oldMillis = result.getOldObj().getModTime().getNano() / 1000;

                if (newMillis > (oldMillis + 2400000))  // update max. every 40 min
                {
                    sendUpdate = true;
                }
            } else {
                sendUpdate = true;  // value has changed, send update via channel
            }

        }
        return sendUpdate;
    }


    public synchronized void setSendQ(IRCMessageQueue s) {
        sendQ = s;

        if (extApp != null) {
            extApp.setSendQ(s);
        }
    }

    public synchronized IRCMessageQueue getSendQ() {
        return sendQ;
    }


    String getLastEntryTime(int tableID) {

        if (extApp != null) {

            Instant d = extApp.getLastEntryDate(tableID);

            if (d != null) {
                return parseDateFormat.format(d);
            }
        }

        return "DBERROR";
    }


    public void run() {

        int dumpUserDBTimer = 60;
        int sendlistTableID = 0;

        while (true) {

            if (timer > 0) {
                timer--;
            }
            channelTimeout++;

            switch (state) {
                case 0:  // wait for network to start

                    if (getSendQ() != null) {
                        state = 1;
                    }
                    break;

                case 1:
                    // connect to db
                    state = 2;
                    timer = 200;
                    break;

                case 2:   // choose server
                    sendlistTableID = getState2SendlistTableID(sendlistTableID);
                    break;

                case 3:
                    sendlistTableID = getState3SendlistTableID(sendlistTableID);
                    break;

                case 4:
                    getState4SendListTableId(sendlistTableID);
                    break;

                case 5: // sendlist processing
                    getState5SendListTableId();
                    break;

                case 6:
                    getState6SendListTableId();
                    break;


                case 7: // standby state after initialization
                    getState7SendListTableId();
                    break;

                case 10:
                    // disconnect db
                    state = 0;
                    timer = 0;
                    acceptPublicUpdates = false;
                    break;

                case 11:
                    if (timer == 0) {
                        System.exit(0);
                    }
                    break;
            }


            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                LOGGER.warn("sleep interrupted " + e);
            }


            if (!dumpUserDBFileName.equals("none")) {
                if (dumpUserDBTimer <= 0) {
                    dumpUserDBTimer = 300;

                    try (var writer = new OutputStreamWriter(new FileOutputStream(dumpUserDBFileName), UTF_8)) {
                        for (UserObject o :  user.values()) {
                            writer.append(o.getNick()).append(" ")
                                    .append(o.getName()).append(" ")
                                    .append(o.getHost()).append(" ")
                                    .append(Boolean.toString(o.isOp()))
                                    .append("\n");
                        }
                    } catch (IOException e) {
                        LOGGER.warn("dumpUser failed " + e);
                    } catch (java.util.ConcurrentModificationException e) {
                        LOGGER.warn("dumpUser failed " + e);
                        dumpUserDBTimer = 3; // try again
                    }

                } else {
                    dumpUserDBTimer--;
                }
            }
        }
    }

    private void getState7SendListTableId() {
        if (getSendQ() == null) {
            state = 10; // disconnect DB
            reconnectReason = "getSendQ in state 7";
        } else {
            if (channelTimeout > 600) // 10 minutes with no IRCDDB msg in channel
            {
                state = 10;
                reconnectReason = "timeout waiting for IRCDDB msg";
            }
        }
    }

    private void getState6SendListTableId() {
        if (getSendQ() == null) {
            state = 10; // disconnect DB
            reconnectReason = "getSendQ in state 6";
        } else {
            UserObject me = user.get(myNick);

            if ((me != null) && (currentServer != null)) {
                UserObject other = user.get(currentServer);

                if ((other != null) && !me.isOp() && other.isOp()
                        && other.getNick().startsWith("s-") && me.getNick().startsWith("s-")) {
                    IRCMessage m2 = new IRCMessage();
                    m2.command = "PRIVMSG";
                    m2.numParams = 2;
                    m2.params[0] = other.getNick();
                    m2.params[1] = "OP_BEG";

                    IRCMessageQueue q = getSendQ();
                    if (q != null) {
                        q.putMessage(m2);
                    }
                }


                LOGGER.debug("IRCDDBApp: state=6");
                enablePublicUpdates();
                state = 7;
                channelTimeout = 0;
            }
        }
    }

    private void getState5SendListTableId() {
        if (getSendQ() == null) {
            state = 10; // disconnect DB
            reconnectReason = "getSendQ in state 5";
        } else if (timer == 0) {
            state = 10;
            reconnectReason = "timeout in state 5";

            IRCMessage m = new IRCMessage();
            m.command = "QUIT";
            m.numParams = 1;
            m.params[0] = "timeout SENDLIST";

            IRCMessageQueue q = getSendQ();
            if (q != null) {
                q.putMessage(m);
            }
        }
    }

    private void getState4SendListTableId(int sendlistTableID) {
        if (getSendQ() == null) {
            state = 10; // disconnect DB
            reconnectReason = "getSendQ in state 4";
        } else {
            if (extApp.needsDatabaseUpdate(sendlistTableID)) {
                IRCMessage m = new IRCMessage();
                m.command = "PRIVMSG";
                m.numParams = 2;
                m.params[0] = currentServer;
                m.params[1] = "SENDLIST" + getTableIDString(sendlistTableID, true)
                        + " " + getLastEntryTime(sendlistTableID);

                IRCMessageQueue q = getSendQ();
                if (q != null) {
                    q.putMessage(m);
                }

                state = 5; // wait for answers
            } else {
                state = 3; // don't send SENDLIST for this table, go to next table
            }
        }
    }

    private int getState3SendlistTableID(int sendlistTableID) {
        if (getSendQ() == null) {
            state = 10; // disconnect DB
            reconnectReason = "getSendQ in state 3";
        } else {
            sendlistTableID--;
            if (sendlistTableID < 0) {
                state = 6; // end of sendlist
            } else {
                LOGGER.debug("IRCDDBApp: state=3 tableID=" + sendlistTableID);
                state = 4; // send "SENDLIST"
                timer = 900; // 15 minutes max for update
            }
        }
        return sendlistTableID;
    }

    private int getState2SendlistTableID(int sendlistTableID) {
        LOGGER.debug("IRCDDBApp: state=2 choose new 's-'-user");
        if (getSendQ() == null) {
            state = 10;
            reconnectReason = "getSendQ in state 2";
        } else {
            if (findServerUser()) {
                sendlistTableID = numberOfTablesToSync;

                IRCMessage m2 = new IRCMessage();
                m2.command = "PRIVMSG";
                m2.numParams = 2;
                m2.params[0] = currentServer;
                m2.params[1] = "IRCDDB " + parseDateFormat.format(Date.from(startupTime)) + " " +
                        reconnectReason;

                IRCMessageQueue q = getSendQ();
                if (q != null) {
                    q.putMessage(m2);
                }

                if (rptrLocation != null) {
                    m2 = new IRCMessage();
                    m2.command = "PRIVMSG";
                    m2.numParams = 2;
                    m2.params[0] = currentServer;
                    m2.params[1] = "IRCDDB QTH: " + rptrLocation;
                    q = getSendQ();
                    if (q != null) {
                        q.putMessage(m2);
                    }
                }

                if ((rptrFrequencies != null) && (numRptrFreq > 0)) {
                    int i;

                    for (i = 0; i < numRptrFreq; i++) {
                        if (rptrFrequencies[i] != null) {
                            m2 = new IRCMessage();
                            m2.command = "PRIVMSG";
                            m2.numParams = 2;
                            m2.params[0] = currentServer;
                            m2.params[1] = "IRCDDB QRG: " + rptrFrequencies[i];
                            q = getSendQ();
                            if (q != null) {
                                q.putMessage(m2);
                            }
                        }
                    }
                }

                if ((rptrInfoURL != null) && (!rptrInfoURL.isEmpty())) {
                    m2 = new IRCMessage();
                    m2.command = "PRIVMSG";
                    m2.numParams = 2;
                    m2.params[0] = currentServer;
                    m2.params[1] = "IRCDDB URL: " + rptrInfoURL;
                    q = getSendQ();
                    if (q != null) {
                        q.putMessage(m2);
                    }
                }


                if (extApp != null) {
                    state = 3; // next: send "SENDLIST"
                } else {
                    state = 6; // next: enablePublicUpdates
                }
            } else if (timer == 0) {
                state = 10;
                reconnectReason = "timeout in state 2";

                IRCMessage m = new IRCMessage();
                m.command = "QUIT";
                m.numParams = 1;
                m.params[0] = "no op user with 's-' found.";

                IRCMessageQueue q = getSendQ();
                if (q != null) {
                    q.putMessage(m);
                }
            }
        }
        return sendlistTableID;
    }


}




