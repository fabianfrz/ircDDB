/*

ircDDB

Copyright (C) 2010   Michael Dirska, DL1BFF (dl1bff@mdx.de)
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

package net.ircDDB.irc;


import net.ircDDB.IRCApplication;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;


import java.io.InputStream;
import java.io.IOException;


public class IRCClient implements Runnable {
    private static final Logger LOGGER = LogManager.getLogger(IRCClient.class);

    private IRCReceiver recv;
    private Thread recvThread;

    private final IRCApplication app;

    private final String host;
    private final int port;
    private final IRCProtocol proto;
    private final SocketConnection socketConnection;
    private IRCMessageQueue recvQ;
    private IRCMessageQueue sendQ;


    public IRCClient(IRCApplication a, String h, int p, String ch,
                     String dbg_chan, String n, String[] u, String pass, String version) {
        app = a;
        host = h;
        port = p;
        proto = new IRCProtocol(a, ch, dbg_chan, n, u, pass, version);
        socketConnection = new SocketConnection();
    }


    boolean init() {

        if (socketConnection.initSocket(host, port)) return false;

        InputStream is;
        try {
            is = socketConnection.getInputStream();
        } catch (IOException e) {
            LOGGER.error("IRCClient/getInputStream: ", e);
            return false;
        }


        recvQ = new IRCMessageQueue();
        sendQ = new IRCMessageQueue();
        recv = new IRCReceiver(is, recvQ);

        recvThread = new Thread(recv);
        try {
            recvThread.start();
        } catch (IllegalThreadStateException e) {
            LOGGER.error("IRCClient/Thread.start: ", e);
            return false;
        }
        proto.setNetworkReady(true);

        return true;
    }


    void closeConnection() {
        if (app != null) {
            app.setSendQ(null);
            app.userListReset();
        }

        socketConnection.close();

        recv = null;
        recvThread = null;
        recvQ = null;
        sendQ = null;

        proto.setNetworkReady(false);
    }

    public void run() {
        var machine = new IRCClientStateMachine(this, proto, recvQ, sendQ, socketConnection);

        while (true) {
            machine.doAct();
        }

    }
}

